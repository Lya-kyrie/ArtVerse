package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Review agent for quality and consistency analysis of storyboards.
 * Read-only — uses AgentScope subagents for parallel multi-dimensional review
 * (camera language, character consistency, pacing, continuity).
 * No write tools, no DEGRADED fallback.
 */
@Component
@RequiredArgsConstructor
public class MangaReviewAgentNode implements MangaWorkflowNodeHandler {

    private final MangaAgentExecutionSupport support;

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.REVIEW;
    }

    @Override
    public MangaWorkflowResult run(MangaWorkflowExecutionContext context) {
        List<AgentMessage> messages = support.prepareAgentMessages(context);
        support.syncWorkspace(context);
        AgentRunRequest request = support.buildRunRequest(context, messages, AgentTaskType.MANGA_REVIEW);
        return support.verifyToolContract(context, route(), support.executeRequest(context, request, false));
    }

    @Override
    public MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        List<AgentMessage> messages = support.prepareAgentMessages(context);
        streamContext.sendRunEvent(AgentRunEvent.step(
                MangaWorkflowNode.GENERATING.name(),
                "running",
                "calling review agent",
                Map.of("provider", context.modelSpec().provider(), "model", context.modelSpec().model())
        ));
        support.syncWorkspace(context);
        AgentRunRequest request = support.buildRunRequest(context, messages, AgentTaskType.MANGA_REVIEW);
        return support.verifyToolContract(context, route(),
                support.executeStreamedRequest(context, streamContext, request, false));
    }
}
