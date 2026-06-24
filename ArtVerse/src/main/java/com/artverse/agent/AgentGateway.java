package com.artverse.agent;

import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AgentGateway {
    Flux<String> streamChat(AgentRunRequest request);
    Flux<AgentEvent> streamEvents(AgentRunRequest request);
    Mono<String> generateText(AgentRunRequest request);
}
