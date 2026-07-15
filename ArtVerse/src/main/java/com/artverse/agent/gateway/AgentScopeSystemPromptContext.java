package com.artverse.agent.gateway;

/** Per-call, server-owned system prompt content carried by RuntimeContext. */
record AgentScopeSystemPromptContext(String content) {

    AgentScopeSystemPromptContext {
        content = content == null ? "" : content;
    }

    boolean isBlank() {
        return content.isBlank();
    }
}
