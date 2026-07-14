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
 * The sole mutating agent in the system. Generates, edits, and persists storyboard
 * scenes. Includes write-guard validation, DEGRADED fallback when tools succeed
 * but the final response fails, and HITL confirmation for destructive operations.
 */
@Component
@RequiredArgsConstructor
public class MangaStoryboardAgentNode implements MangaWorkflowNodeHandler {

    private final MangaAgentExecutionSupport support;

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.STORYBOARD;
    }

    @Override
    public MangaWorkflowResult run(MangaWorkflowExecutionContext context) {
        List<AgentMessage> messages = support.prepareAgentMessages(context);
        support.syncWorkspace(context);
        AgentRunRequest request = support.buildRunRequest(context, messages, AgentTaskType.MANGA_STORYBOARD);
        return support.verifyToolContract(context, route(), support.executeRequest(context, request, true));
    }

    @Override
    public MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        List<AgentMessage> messages = support.prepareAgentMessages(context);
        streamContext.sendRunEvent(AgentRunEvent.step(
                MangaWorkflowNode.GENERATING.name(),
                "running",
                "calling storyboard agent to generate content",
                Map.of("provider", context.modelSpec().provider(), "model", context.modelSpec().model())
        ));
        support.syncWorkspace(context);
        AgentRunRequest request = support.buildRunRequest(context, messages, AgentTaskType.MANGA_STORYBOARD);
        return support.verifyToolContract(context, route(),
                support.executeStreamedRequest(context, streamContext, request, true));
    }
}
