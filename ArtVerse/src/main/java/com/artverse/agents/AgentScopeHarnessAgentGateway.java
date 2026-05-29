package com.artverse.agents;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Primary
public class AgentScopeHarnessAgentGateway implements HarnessAgentGateway {

    private final Model model;
    private final Path workspace;
    private final CompactionConfig compactionConfig;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public AgentScopeHarnessAgentGateway(
            Model model,
            @Qualifier("agentScopeWorkspace") Path workspace,
            CompactionConfig compactionConfig) {
        this.model = model;
        this.workspace = workspace;
        this.compactionConfig = compactionConfig;
    }

    @Override
    public Flux<String> streamChat(AgentRunRequest request) {
        HarnessAgent agent = getOrCreateAgent(request);
        RuntimeContext ctx = buildRuntimeContext(request);
        List<Msg> messages = convertMessages(request);

        return agent.stream(messages, ctx)
                .filter(e -> e.getType() != EventType.AGENT_RESULT
                        && !e.isLast()
                        && e.getMessage() != null
                        && e.getMessage().getTextContent() != null)
                .map(e -> e.getMessage().getTextContent());
    }

    @Override
    public Mono<String> generateText(AgentRunRequest request) {
        HarnessAgent agent = getOrCreateAgent(request);
        RuntimeContext ctx = buildRuntimeContext(request);
        List<Msg> messages = convertMessages(request);

        return agent.call(messages, ctx)
                .map(Msg::getTextContent);
    }

    private HarnessAgent getOrCreateAgent(AgentRunRequest request) {
        String agentKey = "story-" + request.storyId();
        return agentCache.computeIfAbsent(agentKey, k -> buildAgent(request));
    }

    private HarnessAgent buildAgent(AgentRunRequest request) {
        return HarnessAgent.builder()
                .name("artverse-story-" + request.storyId())
                .sysPrompt("你是一个帮助用户创作小说和漫画的AI助手。")
                .model(model)
                .workspace(workspace)
                .compaction(compactionConfig)
                .build();
    }

    private RuntimeContext buildRuntimeContext(AgentRunRequest request) {
        return RuntimeContext.builder()
                .sessionId("story-" + request.storyId() + "-chapter-" + request.chapterId()
                        + "-" + request.taskType().name().toLowerCase())
                .userId(request.userId())
                .build();
    }

    private List<Msg> convertMessages(AgentRunRequest request) {
        return request.messages().stream()
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
