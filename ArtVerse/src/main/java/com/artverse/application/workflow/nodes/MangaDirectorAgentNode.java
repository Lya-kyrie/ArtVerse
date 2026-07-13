package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MangaDirectorAgentNode implements MangaWorkflowNodeHandler {

    private final AgentScopeHarnessAgentGateway harnessAgentGateway;
    private final ArtVerseProperties properties;
    private final MangaDirectorAgentSupport support;

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.DIRECTOR;
    }

    @Override
    public Map<String, Object> run(MangaWorkflowExecutionContext context) {
        List<AgentMessage> messages = support.prepareAgentMessages(context);
        support.syncWorkspace(context);
        AgentRunRequest request = support.buildRunRequest(context, messages);
        try {
            String reply = harnessAgentGateway.generateText(request).block();
            support.throwIfWaitingForUser(context);
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            support.saveReply(context, reply);
            return Map.of("reply", reply);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (BusinessException e) {
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return support.fallbackAfterToolSuccess(context, e.getMessage());
            }
            support.saveFailureMessage(context, e.getMessage());
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return support.fallbackAfterToolSuccess(context, error);
            }
            support.saveFailureMessage(context, error);
            throw new BusinessException(502, "Agent service failed: " + error);
        }
    }

    @Override
    public Map<String, Object> stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        List<AgentMessage> messages = support.prepareAgentMessages(context);
        streamContext.sink().sendRunEvent(streamContext.run(), AgentRunEvent.step(
                MangaWorkflowNode.GENERATING.name(),
                "running",
                "calling agent to generate content",
                Map.of("provider", context.modelSpec().provider(), "model", context.modelSpec().model())
        ));
        support.syncWorkspace(context);
        AgentRunRequest request = support.buildRunRequest(context, messages);
        return executeStreamedRequest(context, streamContext, request);
    }

    private Map<String, Object> executeStreamedRequest(MangaWorkflowExecutionContext context,
                                                       MangaWorkflowStreamContext streamContext,
                                                       AgentRunRequest request) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> {
                        if (context.toolState().isCancelled()) {
                            throw new AgentRunTerminatedException(context.requestId(), context.user().getId(), context.chapter().getId());
                        }
                        streamContext.sink().recordProgress(streamContext.run(), phaseFor(event));
                        support.mapAgentScopeEvent(event).ifPresent(mapped -> {
                        if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                            reply.append(mapped.text());
                        }
                        streamContext.sink().sendRunEvent(streamContext.run(), mapped);
                        });
                    })
                    .timeout(
                            Mono.delay(Duration.ofSeconds(Math.max(1, properties.getAgent().getFirstEventTimeoutSeconds()))),
                            mapped -> Mono.delay(agentIdleTimeout(mapped))
                    )
                    .blockLast();
            finished.set(true);
            support.throwIfWaitingForUser(context);
        } catch (AgentRunTerminatedException e) {
            log.debug("Agent run terminated by concurrent cancel: requestId={} userId={} chapterId={}",
                    e.requestId(), e.userId(), e.chapterId());
            return Map.of("reply", "");
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (Exception e) {
            if (context.toolState().isCancelled()) {
                return Map.of("reply", "");
            }
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return support.fallbackAfterToolSuccess(context, error);
            }
            support.saveFailureMessage(context, error);
            throw new BusinessException(502, "Agent service failed: " + error);
        }

        String finalReply = reply.toString().trim();
        if (context.toolState().isCancelled()) {
            return Map.of("reply", "");
        }
        if (!finished.get() || finalReply.isBlank()) {
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return support.fallbackAfterToolSuccess(context, "Agent returned empty response");
            }
            throw new BusinessException(502, "Agent returned empty response");
        }

        support.saveReply(context, finalReply);
        return Map.of("reply", finalReply);
    }

    private Duration agentIdleTimeout(io.agentscope.core.event.AgentEvent event) {
        return Duration.ofSeconds("TOOL".equals(phaseFor(event))
                ? Math.max(1, properties.getAgent().getToolIdleTimeoutSeconds())
                : Math.max(1, properties.getAgent().getModelIdleTimeoutSeconds()));
    }

    private String phaseFor(io.agentscope.core.event.AgentEvent event) {
        return support.mapAgentScopeEvent(event)
                .filter(mapped -> "tool".equals(mapped.phase()))
                .isPresent() ? "TOOL" : "MODEL";
    }
}
