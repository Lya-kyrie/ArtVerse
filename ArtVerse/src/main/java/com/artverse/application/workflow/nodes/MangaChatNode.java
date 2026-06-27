package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.config.ArtVerseProperties;
import org.springframework.stereotype.Component;

/**
 * LLM-powered conversational chat node.
 * <p>
 * The agent can answer questions about the current chapter, explain manga concepts,
 * and provide creative suggestions — all read-only. It has access to
 * {@code context-tools} for chapter state lookup.
 */
@Component
public class MangaChatNode extends AbstractLlmNode {

    public MangaChatNode(
            MangaAgentConversationService mangaAgentConversationService,
            AgentScopeHarnessAgentGateway harnessAgentGateway,
            AgentWorkspaceSyncService agentWorkspaceSyncService,
            ApiKeyService apiKeyService,
            ArtVerseProperties properties,
            MangaAgentRunService mangaAgentRunService) {
        super(mangaAgentConversationService, harnessAgentGateway, agentWorkspaceSyncService,
                apiKeyService, properties, mangaAgentRunService);
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.CHAT;
    }

    @Override
    public AgentTaskType agentTaskType() {
        return AgentTaskType.MANGA_CHAT;
    }
}
