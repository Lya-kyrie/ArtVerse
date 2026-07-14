package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceService;
import com.artverse.agent.MangaAgentPromptProvider;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.config.ArtVerseProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeAgentFactory {

    private final Model model;
    private final CompactionConfig compactionConfig;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ArtVerseProperties properties;
    private final AgentWorkspaceService agentWorkspaceService;
    private final MangaAgentPromptProvider promptProvider;
    private final AgentStateStore agentStateStore;
    private final AgentToolConfigurationRegistry toolConfigurationRegistry;
    private Cache<String, HarnessAgent> agents;

    @PostConstruct
    void initializeCache() {
        agents = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.getAgent().getAgentCacheMaxSize()))
                .expireAfterAccess(Duration.ofMinutes(
                        Math.max(1, properties.getAgent().getAgentCacheExpireAfterMinutes())))
                .removalListener((String key, HarnessAgent agent, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (agent != null) {
                        agent.close();
                    }
                })
                .build();
    }

    public HarnessAgent getOrCreate(AgentRunRequest request) {
        Path requestWorkspace = agentWorkspaceService.workspaceFor(request);
        AgentModelSpec fallbackSpec = defaultModelSpec(request.llmApiKey());
        String agentKey = buildAgentCacheKey(request, fallbackSpec, requestWorkspace,
                promptProvider.promptVersionFor(request.taskType()));
        return agents.get(agentKey, key -> buildAgent(request, requestWorkspace, fallbackSpec));
    }

    private HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace, AgentModelSpec fallbackSpec) {
        AgentModelSpec modelSpec = request.modelSpec() != null
                ? request.modelSpec()
                : fallbackSpec;
        Model effectiveModel = resolveModel(request.llmApiKey(), modelSpec);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("artverse-story-" + request.storyId() + "-ch" + request.chapterId())
                .sysPrompt(promptProvider.promptFor(request.taskType()))
                .model(effectiveModel)
                .workspace(requestWorkspace)
                .compaction(compactionConfig)
                .stateStore(agentStateStore)
                .enablePendingToolRecovery(true)
                .disableShellTool()
                .disableFilesystemTools();
        for (var declaration : request.taskType().subagentDeclarations()) {
            builder.subagent(declaration);
        }
        HarnessAgent agent = builder.build();
        toolConfigurationRegistry.configure(agent.getToolkit(), request.taskType());
        return agent;
    }

    private Model resolveModel(String llmApiKey, AgentModelSpec modelSpec) {
        if (hasApiKey(llmApiKey)) {
            log.info("Using user-provided {} API key for model: {}", modelSpec.provider(), modelSpec.model());
            return buildChatModel(llmApiKey, modelSpec);
        }
        return model;
    }

    /**
     * Build a chat model for the given provider.
     * All currently supported providers (deepseek, openai, openroute) use the
     * OpenAI-compatible protocol. Non-compatible providers can be added here.
     */
    private OpenAIChatModel buildChatModel(String llmApiKey, AgentModelSpec modelSpec) {
        return OpenAIChatModel.builder()
                .apiKey(llmApiKey)
                .modelName(modelSpec.model())
                .baseUrl(modelSpec.baseUrl())
                .stream(true)
                .build();
    }

    private AgentModelSpec defaultModelSpec(String llmApiKey) {
        return agentModelSpecFactory.defaultLlm(llmApiKey);
    }

    private static boolean hasApiKey(String key) {
        return key != null && !key.isBlank();
    }

    static String buildAgentCacheKey(AgentRunRequest request, AgentModelSpec fallbackSpec, Path workspace,
                                     String promptVersion) {
        AgentModelSpec spec = request.modelSpec() != null ? request.modelSpec() : fallbackSpec;
        return String.join(":",
                "user", nullToKey(request.userId()),
                "story", String.valueOf(request.storyId()),
                "chapter", String.valueOf(request.chapterId()),
                "conversation", nullToKey(request.conversationId() == null ? null : request.conversationId().toString()),
                "task", request.taskType().name(),
                "provider", nullToKey(spec.provider()),
                "model", nullToKey(spec.model()),
                "baseUrl", AgentModelSpecFactory.shortHash(spec.baseUrl()),
                "key", nullToKey(spec.apiKeyHash()),
                "prompt", nullToKey(promptVersion),
                "workspace", workspace == null ? "none" : AgentModelSpecFactory.shortHash(workspace.toAbsolutePath().normalize().toString())
        );
    }

    private static String nullToKey(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
