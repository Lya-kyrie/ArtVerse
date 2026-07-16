package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChapterNovelRevision;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunArtifact;
import com.artverse.domain.NovelContentRevisionSource;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NovelContentArtifactService {

    public static final String ARTIFACT_TYPE = "NOVEL_CONTENT_DRAFT";
    private static final int MAX_DRAFT_ATTEMPTS = 3;

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterNovelRevisionRepository revisionRepository;
    private final NovelContentService novelContentService;
    private final ObjectMapper objectMapper;
    private final AgentRunLeaseService leaseService;

    @Transactional
    public ArtifactView draft(String content, MangaAgentRuntimeContext context) {
        String normalized = normalize(content);
        MangaAgentRun run = requireRun(context.userId(), context.chapterId(), context.requestId());
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(context.chapterId(), context.userId())
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        long baseVersion = chapter.getVersion() == null ? 0L : chapter.getVersion();
        String contentHash = ToolIdempotencyService.sha256(normalized);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", normalized);
        payload.put("content_hash", contentHash);
        payload.put("base_version", baseVersion);
        payload.put("word_count", normalized.length());
        payload.put("current_word_count", chapter.getNovelContent() == null ? 0 : chapter.getNovelContent().trim().length());
        payload.put("chapter_id", chapter.getId());
        payload.put("story_id", chapter.getStory().getId());
        String payloadJson = writeJson(payload);
        String checksum = ToolIdempotencyService.sha256(payloadJson);
        return artifactRepository.findFirstByRunIdAndChecksumOrderByCreatedAtDesc(run.getId(), checksum)
                .map(this::toView)
                .orElseGet(() -> {
                    long attempts = artifactRepository.countByRunIdAndArtifactType(run.getId(), ARTIFACT_TYPE);
                    if (attempts >= MAX_DRAFT_ATTEMPTS) {
                        throw new BusinessException(409, "Novel content draft rewrite limit reached");
                    }
                    MangaAgentRunArtifact artifact = new MangaAgentRunArtifact();
                    artifact.setRun(run);
                    artifact.setArtifactType(ARTIFACT_TYPE);
                    artifact.setStatus("VALIDATED");
                    artifact.setSchemaVersion("1");
                    artifact.setPayload(payloadJson);
                    artifact.setEvaluation(writeJson(Map.of(
                            "content_hash", contentHash,
                            "base_version", baseVersion,
                            "word_count", normalized.length(),
                            "quality_passed", true
                    )));
                    artifact.setChecksum(checksum);
                    return toView(artifactRepository.save(artifact));
                });
    }

    @Transactional
    public CommitView commit(UUID artifactId, MangaAgentRuntimeContext context, UUID confirmedArtifactId) {
        if (artifactId == null) {
            throw new BusinessException(400, "artifact_id is required");
        }
        if (!artifactId.equals(confirmedArtifactId)) {
            throw new BusinessException(409, "Novel draft was not confirmed for this resume request");
        }
        MangaAgentRunArtifact artifact = artifactRepository.findForCommit(
                        artifactId, context.userId(), context.chapterId())
                .orElseThrow(() -> new BusinessException(404, "Novel content draft not found"));
        if (!ARTIFACT_TYPE.equals(artifact.getArtifactType())) {
            throw new BusinessException(400, "Artifact is not a novel content draft");
        }
        if (leaseService != null && !leaseService.owns(artifact.getRun(), context.fencingToken())) {
            throw new BusinessException(409, "Agent run lease is stale; novel content commit was rejected");
        }
        if ("REJECTED".equals(artifact.getStatus())) {
            throw new BusinessException(409, "Novel content draft was discarded");
        }
        if ("COMMITTED".equals(artifact.getStatus())) {
            ChapterNovelRevision revision = revisionRepository.findByAgentRunArtifactId(artifact.getId()).orElse(null);
            Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(context.chapterId(), context.userId())
                    .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
            return toCommitView(artifact, revision, false, chapter.getVersion());
        }
        if (!"VALIDATED".equals(artifact.getStatus())) {
            throw new BusinessException(409, "Novel content draft is not ready to commit");
        }
        Map<String, Object> payload = readMap(artifact.getPayload());
        long baseVersion = longValue(payload.get("base_version"));
        String content = String.valueOf(payload.getOrDefault("content", ""));
        String contentHash = String.valueOf(payload.getOrDefault("content_hash", ""));
        if (!ToolIdempotencyService.sha256(content).equals(contentHash)) {
            throw new BusinessException(409, "Novel content draft hash mismatch");
        }
        NovelContentService.SaveResult saved = novelContentService.save(
                context.chapterId(), context.userId(), content, baseVersion,
                NovelContentRevisionSource.AI, null, artifact);
        artifact.setStatus("COMMITTED");
        Long committedArtifactId = artifact.getId();
        artifactRepository.findByRunIdOrderByCreatedAtAsc(artifact.getRun().getId()).stream()
                .filter(other -> !other.getId().equals(committedArtifactId))
                .filter(other -> ARTIFACT_TYPE.equals(other.getArtifactType()))
                .filter(other -> "VALIDATED".equals(other.getStatus()))
                .forEach(other -> other.setStatus("SUPERSEDED"));
        artifact = artifactRepository.save(artifact);
        return toCommitView(artifact, saved.revision(), saved.changed(), saved.chapter().getVersion());
    }

    @Transactional
    public ArtifactView reject(Long userId, Long chapterId, UUID requestId, UUID artifactId) {
        MangaAgentRun run = requireRun(userId, chapterId, requestId);
        MangaAgentRunArtifact artifact = artifactRepository.findForCommit(artifactId, userId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "Novel content draft not found"));
        if (!artifact.getRun().getId().equals(run.getId())) {
            throw new BusinessException(404, "Novel content draft not found");
        }
        if (!"COMMITTED".equals(artifact.getStatus())) {
            artifact.setStatus("REJECTED");
        }
        return toView(artifactRepository.save(artifact));
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(Long userId, Long chapterId, UUID requestId) {
        MangaAgentRun run = requireRun(userId, chapterId, requestId);
        return artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(artifact -> ARTIFACT_TYPE.equals(artifact.getArtifactType()))
                .map(this::toView)
                .toList();
    }

    private MangaAgentRun requireRun(Long userId, Long chapterId, UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    private ArtifactView toView(MangaAgentRunArtifact artifact) {
        return new ArtifactView(artifact.getArtifactUuid(), artifact.getRun().getRequestId(),
                artifact.getArtifactType(), artifact.getStatus(), artifact.getSchemaVersion(),
                readMap(artifact.getPayload()), readMap(artifact.getEvaluation()), artifact.getChecksum());
    }

    private CommitView toCommitView(MangaAgentRunArtifact artifact, ChapterNovelRevision revision,
                                    boolean changed, Long chapterVersion) {
        return new CommitView(artifact.getArtifactUuid(), changed, chapterVersion,
                revision == null ? null : revision.getId(),
                revision == null ? null : revision.getRevisionNumber(),
                artifact.getChecksum(), artifact.getStatus());
    }

    private String normalize(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "Novel content draft cannot be empty");
        }
        return content.trim();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize novel content artifact", error);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Failed to read novel content artifact", error);
        }
    }

    public record ArtifactView(UUID artifactId, UUID requestId, String type, String status,
                               String schemaVersion, Map<String, Object> payload,
                               Map<String, Object> evaluation, String checksum) { }

    public record CommitView(UUID artifactId, boolean changed, Long chapterVersion, Long revisionId,
                             Integer revisionNumber, String contentHash, String status) { }
}
