package com.artverse.agents;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;

public interface HarnessAgentGateway {

    Flux<String> streamChat(AgentRunRequest request);

    Flux<AgentEvent> streamEvents(AgentRunRequest request);

    Mono<Msg> generate(AgentRunRequest request);
}
