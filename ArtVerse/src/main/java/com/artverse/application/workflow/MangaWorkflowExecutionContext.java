package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.User;

import java.util.UUID;

public record MangaWorkflowExecutionContext(
        MangaAgentConversation conversation,
        String message,
        UUID requestId,
        String llmApiKey,
        AgentModelSpec modelSpec,
        AgentRunToolStatus.RunState toolState,
        User user,
        Chapter chapter,
        MangaWorkflowContextSnapshot workflowContext,
        boolean persistConversationMessages,
        String stepId
) {
    public MangaWorkflowExecutionContext(MangaAgentConversation conversation, String message, UUID requestId,
                                         String llmApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState, User user, Chapter chapter,
                                         MangaWorkflowContextSnapshot workflowContext) {
        this(conversation, message, requestId, llmApiKey, modelSpec, toolState, user, chapter,
                workflowContext, true, null);
    }

    public MangaWorkflowExecutionContext(MangaAgentConversation conversation, String message, UUID requestId,
                                         String llmApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState, User user, Chapter chapter,
                                         MangaWorkflowContextSnapshot workflowContext,
                                         boolean persistConversationMessages) {
        this(conversation, message, requestId, llmApiKey, modelSpec, toolState, user, chapter,
                workflowContext, persistConversationMessages, null);
    }
}
