package com.artverse.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * Middleware that detects when the {@code ask_user} tool returns a suspended result
 * and triggers an agent interrupt, preventing the agent from continuing execution
 * until the user provides input.
 * <p>
 * This replaces the deprecated {@link io.agentscope.core.hook.Hook}-based
 * AgentScopeHitlSuspendHook.
 */
public class AgentScopeHitlSuspendMiddleware implements MiddlewareBase {

    static final String ASK_USER_TOOL = "ask_user";

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx,
                                     ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ToolResultEndEvent tre
                            && ASK_USER_TOOL.equals(tre.getToolCallName())) {
                        // The ask_user tool has completed; trigger an interrupt
                        // to suspend agent execution and wait for user input.
                        ctx.getAgentState().interruptControl()
                                .trigger(InterruptSource.TOOL, null);
                    }
                });
    }
}