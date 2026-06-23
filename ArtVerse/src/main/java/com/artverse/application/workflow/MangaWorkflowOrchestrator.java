package com.artverse.application.workflow;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentModelSpec;
import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentRunEvent;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.MangaAgentRunService;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import io.agentscope.core.tool.ToolSuspendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaWorkflowOrchestrator {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final HarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final AgentWorkspaceSyncService agentWorkspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final ArtVerseProperties properties;
    private final AgentScopeEventMapper agentScopeEventMapper;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaAgentRunEventPublisher mangaAgentRunEventPublisher;
    private final MangaWorkflowContextAssembler mangaWorkflowContextAssembler;

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }
        var cached = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cached.isPresent()) {
            return Map.of("reply", cached.get().getContent());
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        String deepseekApiKey = requireDeepseekApiKey(user);
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapter.getStory().getId(),
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState)
        );
        return result;
    }

    public Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId, String deepseekApiKey,
                                                 AgentModelSpec modelSpec, AgentRunToolStatus.RunState toolState) {
        MangaWorkflowContextSnapshot workflowContext = mangaWorkflowContextAssembler.assemble(conversation, message);
        log.info("Workflow route for request {} -> {}", effectiveRequestId, workflowContext.route());
        return runLeader(conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();
        MangaAgentRun run = mangaAgentRunService.startOrReuse(conversation, effectiveRequestId, message);
        runRef.set(run);
        sink.sendStatus(run, "智能体开始处理当前章节", effectiveRequestId);

        if (mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId).isPresent()) {
            Map<String, Object> result = runWithToolState(conversation, message, effectiveRequestId, toolState);
            mangaAgentRunService.markSucceeded(conversation, effectiveRequestId, String.valueOf(result.getOrDefault("reply", "")));
            sink.sendDone(run, String.valueOf(result.getOrDefault("reply", "")), effectiveRequestId);
            return;
        }

        String deepseekApiKey = requireDeepseekApiKey(user);
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapterId,
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowStream(conversation, message, effectiveRequestId, sink, toolState,
                        deepseekApiKey, modelSpec, run, user, chapter)
        );

        completeRun(run, sink, chapterId, user, effectiveRequestId, result);
    }

    public Map<String, Object> runLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                         String deepseekApiKey, AgentModelSpec modelSpec,
                                         AgentRunToolStatus.RunState toolState) {
        Chapter chapter = conversation.getChapter();
        User user = conversation.getUser();
        List<AgentMessage> messages = prepareAgentMessages(conversation, message, effectiveRequestId);
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapter.getId(), String.valueOf(user.getId()));

        AgentRunRequest request = buildRunRequest(conversation, messages, modelSpec, deepseekApiKey, effectiveRequestId);
        try {
            String reply = harnessAgentGateway.generateText(request).block(agentRunTimeout());
            throwIfWaitingForUser(toolState);
            if (reply == null || reply.isBlank()) {
                throw new com.artverse.common.BusinessException(502, "Agent returned empty response");
            }
            mangaAgentConversationService.saveMessage(conversation, MessageRole.ASSISTANT, reply, effectiveRequestId);
            return Map.of("reply", reply);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new com.artverse.common.BusinessException(502, "Agent tool suspended without user input");
        } catch (com.artverse.common.BusinessException e) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        conversation, effectiveRequestId, toolState, e.getMessage());
            }
            mangaAgentConversationService.saveFailureMessage(conversation, e.getMessage(), effectiveRequestId);
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        conversation, effectiveRequestId, toolState, error);
            }
            mangaAgentConversationService.saveFailureMessage(conversation, error, effectiveRequestId);
            throw new com.artverse.common.BusinessException(502, "Agent service failed: " + error);
        }
    }

    public Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String deepseekApiKey, AgentModelSpec modelSpec,
                                                 MangaAgentRun run, User user, Chapter chapter) {
        MangaWorkflowContextSnapshot workflowContext = mangaWorkflowContextAssembler.assemble(conversation, message);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.ROUTING.name(),
                "running",
                "正在路由当前任务",
                Map.of("route", workflowContext.route().name())
        ));
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.COLLECTING_CONTEXT.name(),
                "running",
                "正在收集上下文信息",
                Map.of(
                        "storyTitle", workflowContext.storyTitle(),
                        "chapterDisplayName", workflowContext.chapterDisplayName(),
                        "sceneCount", workflowContext.sceneCount(),
                        "imageCount", workflowContext.imageCount(),
                        "warnings", workflowContext.warnings()
                )
        ));
        List<AgentMessage> messages = prepareAgentMessages(conversation, message, effectiveRequestId);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.GENERATING.name(),
                "running",
                "正在调用智能体生成内容",
                Map.of("provider", modelSpec.provider(), "model", modelSpec.model())
        ));
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapter.getId(), String.valueOf(user.getId()));
        AgentRunRequest request = buildRunRequest(conversation, messages, modelSpec, deepseekApiKey, effectiveRequestId);
        Map<String, Object> response = executeStreamedRequest(run, sink, toolState, request, chapter, user, effectiveRequestId);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "正在评估生成结果",
                Map.of("degraded", Boolean.TRUE.equals(response.get("agent_final_response_degraded")))
        ));
        return response;
    }

    public Map<String, Object> executeStreamedRequest(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink,
                                                      AgentRunToolStatus.RunState toolState, AgentRunRequest request,
                                                      Chapter chapter, User user, UUID requestId) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> agentScopeEventMapper.map(event).ifPresent(mapped -> {
                        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapter.getId())) {
                            throw new AgentRunTerminatedException();
                        }
                        if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                            reply.append(mapped.text());
                        }
                        sink.sendRunEvent(run, mapped);
                    }))
                    .blockLast(agentRunTimeout());
            finished.set(true);
            throwIfWaitingForUser(toolState);
        } catch (AgentRunTerminatedException e) {
            return Map.of("reply", "");
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new com.artverse.common.BusinessException(502, "Agent tool suspended without user input");
        } catch (Exception e) {
            if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapter.getId())) {
                return Map.of("reply", "");
            }
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        run.getConversation(), requestId, toolState, error);
            }
            mangaAgentConversationService.saveFailureMessage(run.getConversation(), error, requestId);
            throw new com.artverse.common.BusinessException(502, "Agent service failed: " + error);
        }

        String finalReply = reply.toString().trim();
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapter.getId())) {
            return Map.of("reply", "");
        }
        if (!finished.get() || finalReply.isBlank()) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        run.getConversation(), requestId, toolState, "Agent returned empty response");
            }
            throw new com.artverse.common.BusinessException(502, "Agent returned empty response");
        }

        mangaAgentConversationService.saveMessage(run.getConversation(), MessageRole.ASSISTANT, finalReply, requestId);
        return Map.of("reply", finalReply);
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

    public List<AgentMessage> prepareAgentMessages(MangaAgentConversation conversation, String message, UUID requestId) {
        mangaAgentConversationService.saveMessage(conversation, MessageRole.USER, message, requestId);
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(conversation);
        return mangaAgentConversationService.buildMessages(
                conversation.getChapter(),
                conversation.getUser(),
                history,
                message,
                requestId
        );
    }

    public AgentRunRequest buildRunRequest(MangaAgentConversation conversation, List<AgentMessage> messages,
                                           AgentModelSpec modelSpec, String deepseekApiKey, UUID requestId) {
        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of("coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))),
                modelSpec,
                deepseekApiKey,
                requestId,
                conversation.getConversationUuid()
        );
    }

    public String requireDeepseekApiKey(User user) {
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
        return deepseekApiKey;
    }

    public void throwIfWaitingForUser(AgentRunToolStatus.RunState toolState) {
        AgentUserInputRequest waiting = toolState.userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    public Duration agentRunTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getRunTimeoutSeconds()));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static class AgentRunTerminatedException extends RuntimeException {
    }
}
