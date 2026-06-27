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
 * LLM-powered decision-assistance node.
 * <p>
 * The agent helps users make creative decisions by structuring trade-offs,
 * presenting pros/cons for each option, and guiding convergence toward
 * a concrete, actionable choice. Read-only access via {@code context-tools}.
 */
@Component
public class MangaHitlNode extends AbstractLlmNode {

    public MangaHitlNode(
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
        return MangaWorkflowRoute.HITL;
    }

    @Override
    public AgentTaskType agentTaskType() {
        return AgentTaskType.MANGA_HITL;
    }
}
