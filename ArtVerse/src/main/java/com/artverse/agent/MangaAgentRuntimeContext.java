package com.artverse.agent;

import java.util.UUID;

public record MangaAgentRuntimeContext(
        Long userId,
        Long storyId,
        Long chapterId,
        UUID conversationId,
        UUID requestId,
        String cozeApiKey,
        AgentTaskType taskType,
        String stepId,
        Long fencingToken,
        UUID tenantId
) {
    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, String cozeApiKey) {
        this(userId, storyId, chapterId, conversationId, requestId, cozeApiKey,
                AgentTaskType.MANGA_DIRECTOR, "manga-director", 0L, null);
    }

    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, String cozeApiKey,
                                    AgentTaskType taskType) {
        this(userId, storyId, chapterId, conversationId, requestId, cozeApiKey,
                taskType, taskType == null ? "unknown" : taskType.sessionSuffix(), 0L, null);
    }

    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, String cozeApiKey,
                                    AgentTaskType taskType, String stepId) {
        this(userId, storyId, chapterId, conversationId, requestId, cozeApiKey,
                taskType, stepId, 0L, null);
    }

    public MangaAgentRuntimeContext(Long userId, Long storyId, Long chapterId,
                                    UUID conversationId, UUID requestId, String cozeApiKey,
                                    AgentTaskType taskType, String stepId, Long fencingToken) {
        this(userId, storyId, chapterId, conversationId, requestId, cozeApiKey,
                taskType, stepId, fencingToken, null);
    }
}
