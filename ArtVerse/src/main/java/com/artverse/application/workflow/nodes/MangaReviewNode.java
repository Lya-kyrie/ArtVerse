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
 * LLM-powered quality review node.
 * <p>
 * The agent receives a five-dimension audit checklist via system prompt
 * and has read-only access to {@code context-tools} for data gathering.
 * It should NOT modify storyboards, images, or chapter settings.
 */
@Component
public class MangaReviewNode extends AbstractLlmNode {

    public MangaReviewNode(
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
        return MangaWorkflowRoute.REVIEW;
    }

    @Override
    public AgentTaskType agentTaskType() {
        return AgentTaskType.MANGA_REVIEW;
    }
}
