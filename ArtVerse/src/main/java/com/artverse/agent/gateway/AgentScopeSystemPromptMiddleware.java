package com.artverse.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/** Adds server-provided, request-scoped instructions to the Harness system prompt. */
final class AgentScopeSystemPromptMiddleware implements MiddlewareBase {

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String systemPrompt) {
        AgentScopeSystemPromptContext additional = context.get(AgentScopeSystemPromptContext.class);
        if (additional == null || additional.isBlank()) {
            return Mono.just(systemPrompt);
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return Mono.just(additional.content());
        }
        return Mono.just(systemPrompt + "\n\n" + additional.content());
    }
}
