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

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, MangaWorkflowRoute.DIRECTOR, toolState);
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
        return generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapter.getStory().getId(),
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, llmConfig.apiKey(), modelSpec, route, toolState)
        );
    }

    private Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                  UUID effectiveRequestId, String apiKey,
                                                  AgentModelSpec modelSpec, MangaWorkflowRoute route,
                                                  AgentRunToolStatus.RunState toolState) {
        MangaWorkflowContextSnapshot workflowContext = contextAssembler.assemble(conversation, message);
        log.info("Workflow route for request {} -> {}", effectiveRequestId, route);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, apiKey, modelSpec, toolState, workflowContext);
        return nodeRegistry.handlerFor(route).run(context);
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
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();
        MangaAgentRun run = mangaAgentRunService.startOrReuse(conversation, effectiveRequestId, message, route);
        runRef.set(run);
        sink.sendStatus(run, "Agent started processing the current chapter", effectiveRequestId);

        if (mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId).isPresent()) {
            Map<String, Object> result = runWithToolState(conversation, message, effectiveRequestId, route, toolState, llmConfig);
            mangaAgentRunService.markSucceeded(conversation, effectiveRequestId, String.valueOf(result.getOrDefault("reply", "")));
            sink.sendDone(run, String.valueOf(result.getOrDefault("reply", "")), effectiveRequestId);
            return;
        }

        AgentModelSpec modelSpec = agentModelSpecFactory.fromProviderConfig(llmConfig);
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapterId,
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowStream(conversation, message, effectiveRequestId, sink, route, toolState,
                        llmConfig.apiKey(), modelSpec, run)
        );

        completeRun(run, sink, chapterId, user, effectiveRequestId, result);
    }

    public Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 MangaWorkflowRoute route,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String apiKey, AgentModelSpec modelSpec,
                                                 MangaAgentRun run) {
        MangaWorkflowContextSnapshot workflowContext = contextAssembler.assemble(conversation, message);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, apiKey, modelSpec, toolState, workflowContext);

        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.COLLECTING_CONTEXT.name(),
                "running",
                "Collecting chapter context",
                contextAssembler.summary(workflowContext)
        ));

        MangaWorkflowStreamContext streamCtx = new MangaWorkflowStreamContext(run, sink);
        Map<String, Object> response = nodeRegistry.handlerFor(route)
                .stream(context, streamCtx);

        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "Evaluating generated result",
                Map.of("degraded", Boolean.TRUE.equals(response.get("agent_final_response_degraded")))
        ));
        return response;
    }

    public void completeRun(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink, Long chapterId, User user,
                            UUID requestId, Map<String, Object> result) {
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapterId)) {
            sink.complete();
            return;
        }
        String reply = String.valueOf(result.getOrDefault("reply", ""));
        if (Boolean.TRUE.equals(result.get("agent_final_response_degraded"))) {
            mangaAgentRunService.markDegraded(run.getConversation(), requestId, reply,
                    "Agent final response degraded after tool success");
        } else {
            mangaAgentRunService.markSucceeded(run.getConversation(), requestId, reply);
        }
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
                                                           UUID effectiveRequestId, String apiKey,
                                                           AgentModelSpec modelSpec,
                                                           AgentRunToolStatus.RunState toolState,
                                                           MangaWorkflowContextSnapshot workflowContext) {
        return new MangaWorkflowExecutionContext(
                conversation,
                message,
                effectiveRequestId,
                apiKey,
                modelSpec,
                toolState,
                conversation.getUser(),
                conversation.getChapter(),
                workflowContext
        );
    }
}
