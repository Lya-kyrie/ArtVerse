package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunEvent;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.UserProviderConfig;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaWorkflowOrchestrator {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaWorkflowContextAssembler contextAssembler;
    private final MangaWorkflowNodeRegistry nodeRegistry;
    private final MangaWorkflowRouter workflowRouter;
    private final MangaRoutingMetrics routingMetrics;

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, null, toolState);
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, route, toolState,
                requireLlmConfig(conversation.getUser()));
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState,
                                                UserProviderConfig llmConfig) {
        return runWithToolState(conversation, message, effectiveRequestId, route, toolState, llmConfig,
                route == null ? MangaRouteSource.AUTO : MangaRouteSource.RESUME_FIXED);
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState,
                                                UserProviderConfig llmConfig,
                                                MangaRouteSource requestedSource) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }
        var cached = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cached.isPresent()) {
            return Map.of("reply", cached.get().getContent());
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        AgentModelSpec modelSpec = agentModelSpecFactory.fromProviderConfig(llmConfig);
        MangaAgentRun run = mangaAgentRunService.startOrReuse(
                conversation, effectiveRequestId, message,
                route == null ? MangaWorkflowRoute.DIRECTOR : route);
        run = mangaAgentRunService.markRouting(run);
        RoutingDecision decision = resolveRoute(conversation, message, effectiveRequestId, route, modelSpec, llmConfig.apiKey());
        run = mangaAgentRunService.updateRoutingDecision(run, decision, routeSource(decision, requestedSource));
        MangaWorkflowRoute effectiveRoute = decision.route();
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapter.getStory().getId(),
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, llmConfig.apiKey(), modelSpec,
                        effectiveRoute, toolState)
        );
        completeSyncRun(conversation, effectiveRequestId, effectiveRoute, result);
        return result;
    }

    private Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                  UUID effectiveRequestId, String llmApiKey,
                                                  AgentModelSpec modelSpec, MangaWorkflowRoute route,
                                                  AgentRunToolStatus.RunState toolState) {
        MangaWorkflowContextSnapshot workflowContext = contextAssembler.assemble(conversation, message, route);
        log.info("Workflow route for request {} -> {}", effectiveRequestId, route);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, llmApiKey, modelSpec, toolState, workflowContext);
        return nodeRegistry.handlerFor(route).run(context).toPayload();
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef) {
        runStreamLeader(conversation, message, effectiveRequestId, route, toolState, sink, runRef,
                requireLlmConfig(conversation.getUser()));
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef, UserProviderConfig llmConfig) {
        runStreamLeader(conversation, message, effectiveRequestId, route, toolState, sink, runRef, llmConfig,
                route == null ? MangaRouteSource.AUTO : MangaRouteSource.RESUME_FIXED);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef, UserProviderConfig llmConfig,
                                MangaRouteSource requestedSource) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();

        var cachedReply = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cachedReply.isPresent()) {
            replayCachedReply(conversation, effectiveRequestId, cachedReply.get().getContent(), sink, runRef);
            return;
        }

        MangaAgentRun run = mangaAgentRunService.startOrReuse(
                conversation, effectiveRequestId, message,
                route == null ? MangaWorkflowRoute.DIRECTOR : route);
        run = mangaAgentRunService.markRouting(run);
        runRef.set(run);
        sink.sendStatus(run, "Agent started processing the current chapter", effectiveRequestId);

        AgentModelSpec modelSpec = agentModelSpecFactory.fromProviderConfig(llmConfig);
        sink.sendRunEvent(run, AgentRunEvent.step("ROUTER", "running", "正在识别任务并选择智能体", Map.of()));
        RoutingDecision decision = resolveRoute(conversation, message, effectiveRequestId, route, modelSpec, llmConfig.apiKey());
        MangaWorkflowRoute effectiveRoute = decision.route();
        run = mangaAgentRunService.updateRoutingDecision(run, decision, routeSource(decision, requestedSource));
        runRef.set(run);
        MangaAgentRun routedRun = run;
        sink.sendRunEvent(run, AgentRunEvent.step("ROUTER", "completed", "已选择" + effectiveRoute.name() + "智能体",
                Map.of("route", effectiveRoute.name(), "confidence", decision.confidence(),
                        "reasonCode", decision.reasonCode(), "routerVersion", decision.routerVersion())));
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapterId,
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowStream(conversation, message, effectiveRequestId, sink, effectiveRoute, toolState,
                        llmConfig.apiKey(), modelSpec, routedRun)
        );

        completeRun(routedRun, sink, chapterId, user, effectiveRequestId, result);
    }

    private void replayCachedReply(MangaAgentConversation conversation, UUID requestId, String reply,
                                   MangaAgentRunEventPublisher.RunEventSink sink,
                                   AtomicReference<MangaAgentRun> runRef) {
        var persistedRun = mangaAgentRunService.reconcileCachedReply(conversation, requestId, reply);
        if (persistedRun.isEmpty()) {
            sink.sendDone(null, reply, requestId);
            return;
        }

        MangaAgentRun run = persistedRun.get();
        runRef.set(run);
        if (run.getStatus() == MangaAgentRunStatus.SUCCEEDED
                || run.getStatus() == MangaAgentRunStatus.DEGRADED) {
            sink.sendDone(run, reply, requestId);
        } else if (run.getStatus() == MangaAgentRunStatus.FAILED) {
            sink.sendError(run, requestId, run.getErrorMessage());
        } else {
            // A cancellation/interruption remains authoritative even if the reply was saved just before it.
            sink.complete();
        }
    }

    public Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 MangaWorkflowRoute route,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String llmApiKey, AgentModelSpec modelSpec,
                                                 MangaAgentRun run) {
        MangaWorkflowContextSnapshot workflowContext = contextAssembler.assemble(conversation, message, route);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, llmApiKey, modelSpec, toolState, workflowContext);

        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.COLLECTING_CONTEXT.name(),
                "running",
                "Collecting chapter context",
                contextAssembler.summary(workflowContext)
        ));

        MangaWorkflowStreamContext streamCtx = new MangaWorkflowStreamContext(run, sink);
        MangaWorkflowResult response = nodeRegistry.handlerFor(route)
                .stream(context, streamCtx);

        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "Evaluating generated result",
                Map.of("degraded", response.degraded())
        ));
        return response.toPayload();
    }

    public void completeRun(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink, Long chapterId, User user,
                            UUID requestId, Map<String, Object> result) {
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapterId)) {
            sink.complete();
            return;
        }
        String reply = replyFrom(result);
        markRunComplete(run.getConversation(), requestId, run.getRoute(), reply, result);
        sink.sendDone(run, reply, requestId);
    }

    public UserProviderConfig requireLlmConfig(User user) {
        return apiKeyService.requireProviderConfig(
                user,
                ApiKeyService.SLOT_LLM,
                "Please configure an LLM provider API key in Settings before using the manga agent."
        );
    }

    private MangaWorkflowExecutionContext executionContext(MangaAgentConversation conversation, String message,
                                                           UUID effectiveRequestId, String llmApiKey,
                                                           AgentModelSpec modelSpec,
                                                           AgentRunToolStatus.RunState toolState,
                                                           MangaWorkflowContextSnapshot workflowContext) {
        return new MangaWorkflowExecutionContext(
                conversation,
                message,
                effectiveRequestId,
                llmApiKey,
                modelSpec,
                toolState,
                conversation.getUser(),
                conversation.getChapter(),
                workflowContext
        );
    }

    private RoutingDecision resolveRoute(MangaAgentConversation conversation, String message, UUID requestId,
                                         MangaWorkflowRoute requestedRoute, AgentModelSpec modelSpec, String llmApiKey) {
        if (requestedRoute != null) {
            return RoutingDecision.fixed(requestedRoute, "persisted_route");
        }
        return workflowRouter.route(conversation, message, requestId, modelSpec, llmApiKey);
    }

    private MangaRouteSource routeSource(RoutingDecision decision, MangaRouteSource requestedSource) {
        String reason = decision.reasonCode() == null ? "" : decision.reasonCode();
        if (reason.startsWith("shadow:")) {
            return MangaRouteSource.SHADOW;
        }
        if (reason.contains("fallback") || reason.startsWith("invalid_plan:")
                || reason.startsWith("unsupported_capability:")
                || "capability_route_mismatch".equals(reason)) {
            return MangaRouteSource.FALLBACK;
        }
        return requestedSource == null ? MangaRouteSource.AUTO : requestedSource;
    }

    private void completeSyncRun(MangaAgentConversation conversation, UUID requestId,
                                 MangaWorkflowRoute route, Map<String, Object> result) {
        String reply = replyFrom(result);
        markRunComplete(conversation, requestId, route, reply, result);
    }

    private String replyFrom(Map<String, Object> result) {
        return String.valueOf(result.getOrDefault("reply", ""));
    }

    private void markRunComplete(MangaAgentConversation conversation, UUID requestId, MangaWorkflowRoute route,
                                 String reply,
                                  Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("agent_final_response_degraded"))) {
            mangaAgentRunService.markDegraded(conversation, requestId, reply,
                    "Agent final response degraded after tool success");
            routingMetrics.recordRunOutcome(route, "DEGRADED");
        } else {
            mangaAgentRunService.markSucceeded(conversation, requestId, reply);
            routingMetrics.recordRunOutcome(route, "SUCCEEDED");
        }
    }
}
