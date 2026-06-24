package com.artverse.agents;

import java.util.UUID;

public record MangaAgentRuntimeContext(
        Long userId,
        Long storyId,
        Long chapterId,
        UUID conversationId,
        UUID requestId,
        String cozeApiKey
) {
}
