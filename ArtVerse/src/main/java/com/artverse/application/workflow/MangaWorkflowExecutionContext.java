package com.artverse.application.workflow;

import com.artverse.agents.AgentModelSpec;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.User;

import java.util.UUID;

public record MangaWorkflowExecutionContext(
        MangaAgentConversation conversation,
        String message,
        UUID requestId,
        String deepseekApiKey,
        AgentModelSpec modelSpec,
        AgentRunToolStatus.RunState toolState,
        User user,
        Chapter chapter,
        MangaWorkflowContextSnapshot workflowContext
) {
}
