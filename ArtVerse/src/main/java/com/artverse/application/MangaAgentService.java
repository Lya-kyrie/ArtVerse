package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentModelSpec;
import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentRunEvent;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.agentscope.core.tool.ToolSuspendException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaAgentService {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final HarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final AgentWorkspaceSyncService agentWorkspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final ArtVerseProperties properties;
    private final AgentRunToolStatus agentRunToolStatus;
    private final AgentScopeEventMapper agentScopeEventMapper;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaAgentRunEventPublisher mangaAgentRunEventPublisher;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        return mangaAgentConversationService.listMessages(chapterId, user);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        try (AgentRunToolStatus.RunScope scope = agentRunToolStatus.start(user.getId(), chapterId, effectiveRequestId)) {
            return runWithToolState(chapterId, message, effectiveRequestId, user, scope.state());
        }
    }

    public SseEmitter runStream(Long chapterId, String message, UUID requestId, User user) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    effectiveRequestId,
                    event -> mangaAgentRunEventPublisher.sendToolEvent(runRef.get(), emitter, event)
            )) {
                runStreamLeader(chapterId, message, effectiveRequestId, user, ignored.state(), emitter, runRef);
            } catch (AgentUserInputRequiredException e) {
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markWaiting(effectiveRequestId, user.getId(), chapterId, e.request());
                }
                mangaAgentRunEventPublisher.sendUserInputRequested(run, emitter, effectiveRequestId, e.request());
                emitter.complete();
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "智能体请求失败" : e.getMessage();
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markFailed(effectiveRequestId, user.getId(), chapterId, detail);
                }
                mangaAgentRunEventPublisher.sendError(run, emitter, effectiveRequestId, detail);
                emitter.complete();
            }
        });

        return emitter;
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user) {
        SseEmitter emitter = runStream(chapterId, message, requestId, user);
        mangaAgentRunEventPublisher.markAgUiOnly(emitter);
        return emitter;
    }

    public SseEmitter resumeStream(Long chapterId, UUID requestId, String answer, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(user.getId(), chapterId, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        String message = mangaAgentConversationService.resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(requestId, user.getId(), chapterId);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>(waitingRun);
        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    requestId,
                    event -> mangaAgentRunEventPublisher.sendToolEvent(runRef.get(), emitter, event)
            )) {
                mangaAgentRunEventPublisher.sendUserAnswerEvent(waitingRun, emitter, requestId, answer);
                runStreamLeader(chapterId, message, requestId, user, ignored.state(), emitter, runRef);
            } catch (AgentUserInputRequiredException e) {
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markWaiting(requestId, user.getId(), chapterId, e.request());
                }
                mangaAgentRunEventPublisher.sendUserInputRequested(run, emitter, requestId, e.request());
                emitter.complete();
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "智能体请求失败" : e.getMessage();
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markFailed(requestId, user.getId(), chapterId, detail);
                }
                mangaAgentRunEventPublisher.sendError(run, emitter, requestId, detail);
                emitter.complete();
            }
        });
        return emitter;
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(user.getId(), chapterId, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(requestId, user.getId(), chapterId);
        String message = mangaAgentConversationService.resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        try {
            RunResult result = run(chapterId, message, requestId, user);
            mangaAgentRunService.markSucceeded(requestId, user.getId(), chapterId, result.reply());
            return result;
        } catch (Exception e) {
            mangaAgentRunService.markFailed(requestId, user.getId(), chapterId,
                    e.getMessage() == null ? "智能体请求失败" : e.getMessage());
            throw e;
        }
    }

    public Optional<MangaAgentRunService.RunSnapshot> latestOpenRun(Long chapterId, User user) {
        chapterAccessService.requireVisible(chapterId, user.getId());
        return mangaAgentRunService.findLatestOpenRun(user.getId(), chapterId)
                .map(mangaAgentRunService::snapshot);
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        return mangaAgentRunService.findRun(user.getId(), chapterId, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    private RunResult runWithToolState(Long chapterId, String message, UUID effectiveRequestId, User user,
                                       AgentRunToolStatus.RunState toolState) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        var cached = mangaAgentConversationService.findAssistantReply(user.getId(), chapterId, effectiveRequestId);
        if (cached.isPresent()) {
            return new RunResult(cached.get().getContent(), effectiveRequestId);
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
                () -> runLeader(chapterId, message, effectiveRequestId, user, deepseekApiKey, modelSpec, toolState)
        );
        return new RunResult(String.valueOf(result.getOrDefault("reply", "")), effectiveRequestId);
    }

    private void runStreamLeader(Long chapterId, String message, UUID effectiveRequestId, User user,
                                 AgentRunToolStatus.RunState toolState, SseEmitter emitter,
                                 AtomicReference<MangaAgentRun> runRef) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        MangaAgentRun run = mangaAgentRunService.startOrReuse(user, chapter, effectiveRequestId, message);
        runRef.set(run);
        mangaAgentRunEventPublisher.sendStatus(run, emitter, "智能体开始处理当前章节", effectiveRequestId);

        if (mangaAgentConversationService.findAssistantReply(user.getId(), chapterId, effectiveRequestId).isPresent()) {
            RunResult result = runWithToolState(chapterId, message, effectiveRequestId, user, toolState);
            mangaAgentRunService.markSucceeded(effectiveRequestId, user.getId(), chapterId, result.reply());
            mangaAgentRunEventPublisher.sendDone(run, emitter, result.reply(), result.requestId());
            emitter.complete();
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
                () -> {
                    List<AgentMessage> messages = prepareAgentMessages(chapter, user, message, effectiveRequestId);
                    mangaAgentRunEventPublisher.sendRunEvent(
                            run,
                            emitter,
                            AgentRunEvent.of("context_loading", "context", "正在同步故事知识")
                    );
                    agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapterId, String.valueOf(user.getId()));
                    AgentRunRequest request = buildRunRequest(chapter, user, messages, modelSpec, deepseekApiKey, effectiveRequestId);
                    return executeStreamedRequest(run, emitter, toolState, request, chapter, user, effectiveRequestId);
                }
        );

        completeRun(run, emitter, chapterId, user, effectiveRequestId, result);
    }

    private Map<String, Object> runLeader(Long chapterId, String message, UUID effectiveRequestId, User user,
                                          String deepseekApiKey, AgentModelSpec modelSpec,
                                          AgentRunToolStatus.RunState toolState) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        List<AgentMessage> messages = prepareAgentMessages(chapter, user, message, effectiveRequestId);
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapterId, String.valueOf(user.getId()));

        AgentRunRequest request = buildRunRequest(chapter, user, messages, modelSpec, deepseekApiKey, effectiveRequestId);
        try {
            String reply = harnessAgentGateway.generateText(request).block(agentRunTimeout());
            throwIfWaitingForUser(toolState);
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            mangaAgentConversationService.saveMessage(user, chapter, MessageRole.ASSISTANT, reply, effectiveRequestId);
            return Map.of("reply", reply);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new BusinessException(502, "Agent tool suspended without user input");
        } catch (BusinessException e) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        user, chapter, effectiveRequestId, toolState, e.getMessage());
            }
            mangaAgentConversationService.saveFailureMessage(user, chapter, e.getMessage(), effectiveRequestId);
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        user, chapter, effectiveRequestId, toolState, error);
            }
            mangaAgentConversationService.saveFailureMessage(user, chapter, error, effectiveRequestId);
            throw new BusinessException(502, "Agent service failed: " + error);
        }
    }

    private Map<String, Object> executeStreamedRequest(MangaAgentRun run, SseEmitter emitter,
                                                       AgentRunToolStatus.RunState toolState, AgentRunRequest request,
                                                       Chapter chapter, User user, UUID requestId) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> agentScopeEventMapper.map(event).ifPresent(mapped -> {
                        if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                            reply.append(mapped.text());
                        }
                        mangaAgentRunEventPublisher.sendRunEvent(run, emitter, mapped);
                    }))
                    .blockLast(agentRunTimeout());
            finished.set(true);
            throwIfWaitingForUser(toolState);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new BusinessException(502, "Agent tool suspended without user input");
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(user, chapter, requestId, toolState, error);
            }
            mangaAgentConversationService.saveFailureMessage(user, chapter, error, requestId);
            throw new BusinessException(502, "Agent service failed: " + error);
        }

        String finalReply = reply.toString().trim();
        if (!finished.get() || finalReply.isBlank()) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        user, chapter, requestId, toolState, "Agent returned empty response");
            }
            throw new BusinessException(502, "Agent returned empty response");
        }

        mangaAgentConversationService.saveMessage(user, chapter, MessageRole.ASSISTANT, finalReply, requestId);
        return Map.of("reply", finalReply);
    }

    private void completeRun(MangaAgentRun run, SseEmitter emitter, Long chapterId, User user,
                             UUID requestId, Map<String, Object> result) {
        String reply = String.valueOf(result.getOrDefault("reply", ""));
        if (Boolean.TRUE.equals(result.get("agent_final_response_degraded"))) {
            mangaAgentRunService.markDegraded(requestId, user.getId(), chapterId, reply,
                    "Agent final response degraded after tool success");
        } else {
            mangaAgentRunService.markSucceeded(requestId, user.getId(), chapterId, reply);
        }
        mangaAgentRunEventPublisher.sendDone(run, emitter, reply, requestId);
        emitter.complete();
    }

    private List<AgentMessage> prepareAgentMessages(Chapter chapter, User user, String message, UUID requestId) {
        mangaAgentConversationService.saveMessage(user, chapter, MessageRole.USER, message, requestId);
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(chapter.getId(), user);
        return mangaAgentConversationService.buildMessages(chapter, user, history, message, requestId);
    }

    private AgentRunRequest buildRunRequest(Chapter chapter, User user, List<AgentMessage> messages,
                                            AgentModelSpec modelSpec, String deepseekApiKey, UUID requestId) {
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of("coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))),
                modelSpec,
                deepseekApiKey,
                requestId
        );
    }

    private String requireDeepseekApiKey(User user) {
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
        return deepseekApiKey;
    }

    private void throwIfWaitingForUser(AgentRunToolStatus.RunState toolState) {
        AgentUserInputRequest waiting = toolState.userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    private Duration agentRunTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getRunTimeoutSeconds()));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record RunResult(String reply, UUID requestId) {
    }
}
