package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.domain.MessageRole;

abstract class AbstractStaticReplyNode implements MangaWorkflowNodeHandler {

    private final MangaAgentConversationService mangaAgentConversationService;

    protected AbstractStaticReplyNode(MangaAgentConversationService mangaAgentConversationService) {
        this.mangaAgentConversationService = mangaAgentConversationService;
    }

    @Override
    public final MangaWorkflowResult run(MangaWorkflowExecutionContext context) {
        return reply(context);
    }

    @Override
    public final MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        return reply(context);
    }

    protected final MangaWorkflowResult reply(MangaWorkflowExecutionContext context) {
        String reply = responseText(context);
        mangaAgentConversationService.saveMessage(
                context.conversation(),
                MessageRole.ASSISTANT,
                reply,
                context.requestId()
        );
        return MangaWorkflowResult.success(reply);
    }

    protected abstract String responseText(MangaWorkflowExecutionContext context);
}
