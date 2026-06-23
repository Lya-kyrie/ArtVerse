package com.artverse.agents;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Primary
public class AgentScopeHarnessAgentGateway implements HarnessAgentGateway {

    private final AgentScopeAgentFactory agentFactory;
    private final AgentScopeRuntimeContextFactory runtimeContextFactory;

    public AgentScopeHarnessAgentGateway(
            AgentScopeAgentFactory agentFactory,
            AgentScopeRuntimeContextFactory runtimeContextFactory) {
        this.agentFactory = agentFactory;
        this.runtimeContextFactory = runtimeContextFactory;
    }

    @Override
    public Flux<String> streamChat(AgentRunRequest request) {
        return streamEvents(request)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    @Override
    public Flux<AgentEvent> streamEvents(AgentRunRequest request) {
        HarnessAgent agent = agentFactory.getOrCreate(request);
        RuntimeContext ctx = runtimeContextFactory.create(request);
        List<Msg> messages = convertMessages(prepareInputMessages(request));

        return agent.streamEvents(messages, ctx);
    }

    @Override
    public Mono<String> generateText(AgentRunRequest request) {
        HarnessAgent agent = agentFactory.getOrCreate(request);
        RuntimeContext ctx = runtimeContextFactory.create(request);
        List<Msg> messages = convertMessages(prepareInputMessages(request));

        return agent.call(messages, ctx)
                .map(Msg::getTextContent);
    }

    static Long parseUserIdForTool(String userId) {
        return AgentScopeRuntimeContextFactory.parseUserIdForTool(userId);
    }

    static String buildAgentCacheKey(AgentRunRequest request, AgentModelSpec fallbackSpec) {
        return buildAgentCacheKey(request, fallbackSpec, null);
    }

    static String buildAgentCacheKey(AgentRunRequest request, AgentModelSpec fallbackSpec, java.nio.file.Path workspace) {
        return AgentScopeAgentFactory.buildAgentCacheKey(
                request,
                fallbackSpec,
                workspace,
                new MangaAgentPromptProvider().promptVersionFor(request.taskType())
        );
    }

    static RuntimeContext buildRuntimeContextForTest(AgentRunRequest request, AgentSessionIdFactory factory) {
        return new AgentScopeRuntimeContextFactory(factory).create(request);
    }

    static List<AgentMessage> prepareInputMessages(AgentRunRequest request) {
        List<String> systemMessages = new ArrayList<>();
        List<AgentMessage> inputMessages = new ArrayList<>();

        for (AgentMessage message : request.messages()) {
            if ("system".equalsIgnoreCase(message.role())) {
                systemMessages.add(message.content());
            } else {
                inputMessages.add(message);
            }
        }

        if (systemMessages.isEmpty()) {
            return inputMessages;
        }

        String systemPrompt = String.join("\n\n", systemMessages);
        if (inputMessages.isEmpty()) {
            return List.of(new AgentMessage("user", systemPrompt));
        }

        AgentMessage first = inputMessages.get(0);
        List<AgentMessage> prepared = new ArrayList<>(inputMessages);
        prepared.set(0, new AgentMessage(first.role(), systemPrompt + "\n\n" + first.content()));
        return prepared;
    }

    private List<Msg> convertMessages(List<AgentMessage> messages) {
        return messages.stream()
                .map(m -> Msg.builder()
                        .role(convertRole(m.role()))
                        .textContent(m.content())
                        .build())
                .toList();
    }

    private MsgRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            default -> MsgRole.USER;
        };
    }
}
