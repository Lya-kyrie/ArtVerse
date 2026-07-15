package com.artverse.agent.gateway;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeSystemPromptMiddlewareTest {

    private final AgentScopeSystemPromptMiddleware middleware = new AgentScopeSystemPromptMiddleware();

    @Test
    void appendsRequestScopedSystemContentToTheStaticPrompt() {
        RuntimeContext context = RuntimeContext.builder()
                .userId("1")
                .sessionId("session")
                .put(AgentScopeSystemPromptContext.class, new AgentScopeSystemPromptContext("chapter rules"))
                .build();

        assertThat(middleware.onSystemPrompt(null, context, "base rules").block())
                .isEqualTo("base rules\n\nchapter rules");
    }

    @Test
    void leavesTheStaticPromptUnchangedWhenNoRequestScopedContentExists() {
        RuntimeContext context = RuntimeContext.builder().userId("1").sessionId("session").build();

        assertThat(middleware.onSystemPrompt(null, context, "base rules").block())
                .isEqualTo("base rules");
    }
}
