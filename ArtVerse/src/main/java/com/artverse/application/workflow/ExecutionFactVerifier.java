package com.artverse.application.workflow;

import com.artverse.application.StoryboardArtifactService;
import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentConversation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verifies server-owned business facts after an agent has produced a candidate
 * reply. A model statement is never accepted as proof that a mutation happened.
 */
@Component
public class ExecutionFactVerifier {

    private final StoryboardArtifactService artifactService;

    public ExecutionFactVerifier(StoryboardArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public VerifiedFacts verify(MangaAgentConversation conversation, UUID requestId,
                                MangaWorkflowRoute route, MangaWorkflowResult result) {
        if (result == null || result.reply() == null || result.reply().isBlank()) {
            throw new BusinessException(502, "Execution produced no final reply to verify");
        }
        MangaWorkflowRoute safeRoute = route == null ? MangaWorkflowRoute.CONVERSATION : route;
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("route", safeRoute.name());
        facts.put("reply_present", true);

        if (safeRoute == MangaWorkflowRoute.STORYBOARD) {
            StoryboardArtifactService.ArtifactView committed = committedStoryboard(conversation, requestId);
            Object scenesValue = committed.payload().get("scenes");
            if (!(scenesValue instanceof List<?> scenes)) {
                throw new BusinessException(409, "Committed storyboard artifact has no scene list");
            }
            int expectedSceneCount = conversation.getChapter().getImageCount();
            if (scenes.size() != expectedSceneCount) {
                throw new BusinessException(409, "Committed storyboard scene count does not match the chapter");
            }
            facts.put("artifact_id", committed.artifactId().toString());
            facts.put("artifact_status", committed.status());
            facts.put("artifact_checksum", committed.checksum());
            facts.put("scene_count", scenes.size());
            facts.put("chapter_id", conversation.getChapter().getId());
            facts.put("committed", true);
        } else {
            facts.put("committed", false);
        }
        return new VerifiedFacts(schemaFor(safeRoute), Map.copyOf(facts));
    }

    private StoryboardArtifactService.ArtifactView committedStoryboard(MangaAgentConversation conversation,
                                                                        UUID requestId) {
        return artifactService.list(
                        conversation.getUser().getId(),
                        conversation.getChapter().getId(),
                        requestId)
                .stream()
                .filter(artifact -> "STORYBOARD_DRAFT".equals(artifact.type()))
                .filter(artifact -> "COMMITTED".equals(artifact.status()))
                .reduce((first, second) -> {
                    throw new BusinessException(409, "More than one storyboard artifact was committed for this run");
                })
                .orElseThrow(() -> new BusinessException(409,
                        "No committed storyboard artifact exists for this run"));
    }

    private String schemaFor(MangaWorkflowRoute route) {
        return switch (route) {
            case CONVERSATION -> "conversation.reply.v1";
            case CREATIVE -> "creative.guidance.v1";
            case STORYBOARD -> "storyboard.outcome.v1";
            case REVIEW -> "review.report.v1";
            case DIRECTOR -> "director.summary.v1";
        };
    }

    public record VerifiedFacts(String resultSchema, Map<String, Object> facts) {
    }
}
