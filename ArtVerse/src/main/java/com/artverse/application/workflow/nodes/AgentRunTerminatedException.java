package com.artverse.application.workflow.nodes;

import java.util.UUID;

final class AgentRunTerminatedException extends RuntimeException {

    private final UUID requestId;
    private final Long userId;
    private final Long chapterId;

    AgentRunTerminatedException(UUID requestId, Long userId, Long chapterId) {
        super("Agent run terminated");
        this.requestId = requestId;
        this.userId = userId;
        this.chapterId = chapterId;
    }

    UUID requestId() {
        return requestId;
    }

    Long userId() {
        return userId;
    }

    Long chapterId() {
        return chapterId;
    }
}
