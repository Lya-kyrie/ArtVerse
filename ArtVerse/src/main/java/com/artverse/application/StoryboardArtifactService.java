package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunArtifact;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoryboardArtifactService {

    private static final String ARTIFACT_TYPE = "STORYBOARD_DRAFT";
    private static final int MAX_DRAFT_ATTEMPTS = 3;

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final ChapterAccessService chapterAccessService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final SceneService sceneService;
    private final ObjectMapper objectMapper;
    private final AgentRunLeaseService leaseService;

    public record ArtifactView(
            UUID artifactId,
            UUID requestId,
            String type,
            String status,
            String schemaVersion,
            Map<String, Object> payload,
            Map<String, Object> evaluation,
            String checksum
    ) {
    }

    @Transactional
    public ArtifactView createStructuredDraft(Object pages, MangaAgentRuntimeContext context) {
        Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
        List<String> scenes = structuredStoryboardService.normalize(pages, chapter.getImageCount());
        return createDraft(context, chapter, pages, scenes);
    }

    @Transactional
    public ArtifactView createTextDraft(List<String> scenes, MangaAgentRuntimeContext context) {
        Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
        if (scenes == null || scenes.size() != chapter.getImageCount()) {
            throw new BusinessException(400, "Scenes count must equal image count (" + chapter.getImageCount() + ")");
        }
        sceneService.validateScenes(scenes);
        return createDraft(context, chapter, null, scenes);
    }

    @Transactional
    public ArtifactView commit(UUID artifactId, MangaAgentRuntimeContext context) {
        MangaAgentRunArtifact artifact = artifactRepository.findForCommit(
                        artifactId, context.userId(), context.chapterId())
                .orElseThrow(() -> new BusinessException(404, "Storyboard draft not found"));
        if (!leaseService.owns(artifact.getRun(), context.fencingToken())) {
            throw new BusinessException(409, "Agent run lease is stale; storyboard commit was rejected");
        }
        if ("COMMITTED".equals(artifact.getStatus())) {
            return toView(artifact);
        }
        if (!"VALIDATED".equals(artifact.getStatus())) {
            throw new BusinessException(409, "Storyboard draft is not ready to commit");
        }

        Map<String, Object> payload = readMap(artifact.getPayload());
        long expectedVersion = ((Number) payload.getOrDefault("chapter_version", 0)).longValue();
        Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
        long currentVersion = chapter.getVersion() == null ? 0 : chapter.getVersion();
        if (currentVersion != expectedVersion) {
            throw new BusinessException(409, "Chapter changed after storyboard draft creation; regenerate the draft");
        }

        List<String> scenes = objectMapper.convertValue(payload.get("scenes"), new TypeReference<>() { });
        sceneService.updateScenes(chapter.getId(), scenes);
        artifact.setStatus("COMMITTED");
        artifactRepository.findByRunIdOrderByCreatedAtAsc(artifact.getRun().getId()).stream()
                .filter(other -> !other.getId().equals(artifact.getId()))
                .filter(other -> ARTIFACT_TYPE.equals(other.getArtifactType()))
                .filter(other -> "VALIDATED".equals(other.getStatus()))
                .forEach(other -> other.setStatus("SUPERSEDED"));
        return toView(artifactRepository.save(artifact));
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(Long userId, Long chapterId, UUID requestId) {
        MangaAgentRun run = requireRun(userId, chapterId, requestId);
        return artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .map(this::toView)
                .toList();
    }

    private ArtifactView createDraft(MangaAgentRuntimeContext context, Chapter chapter,
                                     Object pages, List<String> scenes) {
        MangaAgentRun run = requireRun(context.userId(), context.chapterId(), context.requestId());
        long chapterVersion = chapter.getVersion() == null ? 0 : chapter.getVersion();
        Map<String, Object> payload = pages == null
                ? Map.of("scenes", scenes, "chapter_version", chapterVersion)
                : Map.of("pages", pages, "scenes", scenes, "chapter_version", chapterVersion);
        String payloadJson = writeJson(payload);
        String checksum = ToolIdempotencyService.sha256(payloadJson);
        return artifactRepository.findFirstByRunIdAndChecksumOrderByCreatedAtDesc(run.getId(), checksum)
                .map(this::toView)
                .orElseGet(() -> {
                    long attempts = artifactRepository.countByRunIdAndArtifactType(run.getId(), ARTIFACT_TYPE);
                    if (attempts >= MAX_DRAFT_ATTEMPTS) {
                        throw new BusinessException(409, "Storyboard evaluation rewrite limit reached");
                    }
                    Map<String, Object> evaluation = Map.of(
                            "evaluator", "storyboard-structure-v1",
                            "score", 100,
                            "quality_passed", true,
                            "issues", List.of(),
                            "page_count", scenes.size(),
                            "rewrite", attempts
                    );
                    MangaAgentRunArtifact artifact = new MangaAgentRunArtifact();
                    artifact.setRun(run);
                    artifact.setArtifactType(ARTIFACT_TYPE);
                    artifact.setStatus("VALIDATED");
                    artifact.setSchemaVersion("1");
                    artifact.setPayload(payloadJson);
                    artifact.setEvaluation(writeJson(evaluation));
                    artifact.setChecksum(checksum);
                    return toView(artifactRepository.save(artifact));
                });
    }

    private MangaAgentRun requireRun(Long userId, Long chapterId, UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required for storyboard artifacts");
        }
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    private ArtifactView toView(MangaAgentRunArtifact artifact) {
        return new ArtifactView(artifact.getArtifactUuid(), artifact.getRun().getRequestId(),
                artifact.getArtifactType(), artifact.getStatus(), artifact.getSchemaVersion(),
                readMap(artifact.getPayload()), readMap(artifact.getEvaluation()), artifact.getChecksum());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize storyboard artifact", error);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Failed to read storyboard artifact", error);
        }
    }
}
