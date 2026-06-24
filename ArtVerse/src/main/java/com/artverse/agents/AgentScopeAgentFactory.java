package com.artverse.agents;

import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeAgentFactory {

    private final Model model;
    private final CompactionConfig compactionConfig;
    private final ArtVerseProperties properties;
    private final AgentWorkspaceService agentWorkspaceService;
    private final MangaAgentPromptProvider promptProvider;
    private final MangaAgentToolkitFactory toolkitFactory;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public HarnessAgent getOrCreate(AgentRunRequest request) {
        Path requestWorkspace = agentWorkspaceService.workspaceFor(request);
        String agentKey = buildAgentCacheKey(request, defaultModelSpec(request.userApiKey()), requestWorkspace,
                promptProvider.promptVersionFor(request.taskType()));
        return agentCache.computeIfAbsent(agentKey, k -> buildAgent(request, requestWorkspace));
    }

    private HarnessAgent buildAgent(AgentRunRequest request, Path requestWorkspace) {
        AgentModelSpec modelSpec = request.modelSpec() != null
                ? request.modelSpec()
                : defaultModelSpec(request.userApiKey());
        Model effectiveModel = resolveModel(request.userApiKey(), modelSpec);
        HarnessAgent agent = HarnessAgent.builder()
                .name("artverse-story-" + request.storyId())
                .sysPrompt(promptProvider.promptFor(request.taskType()))
                .model(effectiveModel)
                .workspace(requestWorkspace)
                .compaction(compactionConfig)
                .enablePendingToolRecovery(true)
                .disableShellTool()
                .disableFilesystemTools()
                .build();
        if (request.taskType() == AgentTaskType.MANGA_DIRECTOR) {
            toolkitFactory.configureMangaDirector(agent.getToolkit(), toolkitFactory.activeGroupsForDirector());
        }
        return agent;
    }

    private Model resolveModel(String userApiKey, AgentModelSpec modelSpec) {
        if (userApiKey != null && !userApiKey.isBlank()) {
            log.info("Using user-provided DeepSeek API key for model");
            return OpenAIChatModel.builder()
                    .apiKey(userApiKey)
                    .modelName(modelSpec.model())
                    .baseUrl(modelSpec.baseUrl())
                    .stream(true)
                    .build();
        }
        return model;
    }

    private AgentModelSpec defaultModelSpec(String userApiKey) {
        if (userApiKey != null && !userApiKey.isBlank()) {
            return new AgentModelSpec(
                    "deepseek",
                    properties.getDeepseek().getBaseUrl(),
                    properties.getDeepseek().getModel(),
                    AgentModelSpecFactory.shortHash(userApiKey)
            );
        }
        return new AgentModelSpec(
                "deepseek",
                properties.getDeepseek().getBaseUrl(),
                properties.getDeepseek().getModel(),
                "env"
        );
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
