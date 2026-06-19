package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentModelSpec;
import com.artverse.agents.AgentRunEvent;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.AgentModelSpecFactory;
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
import com.artverse.persistence.MangaAgentMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.agentscope.core.tool.ToolSuspendException;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
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

    private static final int HISTORY_LIMIT_FOR_AGENT = 20;

    private final MangaAgentMessageRepository mangaAgentMessageRepository;
    private final HarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final AgentWorkspaceSyncService agentWorkspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final ArtVerseProperties properties;
    private final AgentRunToolStatus agentRunToolStatus;
    private final ObjectMapper objectMapper;
    private final AgentScopeEventMapper agentScopeEventMapper;
    private final MangaAgentRunService mangaAgentRunService;
    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        chapterAccessService.requireVisible(chapterId, user.getId());
        return mangaAgentMessageRepository.findByUserIdAndChapterIdOrderByCreatedAtAsc(user.getId(), chapterId);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        try (AgentRunToolStatus.RunScope scope = agentRunToolStatus.start(user.getId(), chapterId, effectiveRequestId)) {
            return runWithToolState(chapterId, message, effectiveRequestId, user, scope.state());
        }
    }

    private RunResult runWithToolState(Long chapterId, String message, UUID effectiveRequestId, User user,
                                       AgentRunToolStatus.RunState toolState) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        var cached = mangaAgentMessageRepository
                .findByUserIdAndRequestIdAndRole(user.getId(), effectiveRequestId, MessageRole.ASSISTANT);
        if (cached.isPresent()) {
            return new RunResult(cached.get().getContent(), effectiveRequestId);
        }

        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);

        Map<String, Object> result;
        result = generationGuardService.executeMangaAgentRun(
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

    public SseEmitter runStream(Long chapterId, String message, UUID requestId, User user) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    effectiveRequestId,
                    event -> sendToolEvent(runRef.get(), emitter, event)
            )) {
                runStreamLeader(chapterId, message, effectiveRequestId, user, ignored.state(), emitter, runRef);
            } catch (AgentUserInputRequiredException e) {
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markWaiting(effectiveRequestId, user.getId(), chapterId, e.request());
                }
                sendUserInputRequested(run, emitter, effectiveRequestId, e.request());
                emitter.complete();
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "智能体请求失败" : e.getMessage();
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markFailed(effectiveRequestId, user.getId(), chapterId, detail);
                }
                sendError(run, emitter, effectiveRequestId, detail);
                emitter.complete();
            }
        });

        return emitter;
    }

    public SseEmitter resumeStream(Long chapterId, UUID requestId, String answer, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(user.getId(), chapterId, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        String message = resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(requestId, user.getId(), chapterId);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>(waitingRun);
        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    requestId,
                    event -> sendToolEvent(runRef.get(), emitter, event)
            )) {
                sendUserAnswerEvent(waitingRun, emitter, requestId, answer);
                runStreamLeader(chapterId, message, requestId, user, ignored.state(), emitter, runRef);
            } catch (AgentUserInputRequiredException e) {
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markWaiting(requestId, user.getId(), chapterId, e.request());
                }
                sendUserInputRequested(run, emitter, requestId, e.request());
                emitter.complete();
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "智能体请求失败" : e.getMessage();
                MangaAgentRun run = runRef.get();
                if (run != null) {
                    mangaAgentRunService.markFailed(requestId, user.getId(), chapterId, detail);
                }
                sendError(run, emitter, requestId, detail);
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
        String message = resumeMessage(waitingRun.getInputMessage(), waiting, answer);
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

    private void runStreamLeader(Long chapterId, String message, UUID effectiveRequestId, User user,
                                 AgentRunToolStatus.RunState toolState, SseEmitter emitter,
                                 AtomicReference<MangaAgentRun> runRef) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        MangaAgentRun run = mangaAgentRunService.startOrReuse(user, chapter, effectiveRequestId, message);
        runRef.set(run);
        sendStatus(run, emitter, "智能体已开始处理当前章节", effectiveRequestId);
        if (mangaAgentMessageRepository.findByUserIdAndRequestIdAndRole(user.getId(), effectiveRequestId, MessageRole.ASSISTANT).isPresent()) {
            RunResult result = runWithToolState(chapterId, message, effectiveRequestId, user, toolState);
            mangaAgentRunService.markSucceeded(effectiveRequestId, user.getId(), chapterId, result.reply());
            sendDone(run, emitter, result.reply(), result.requestId());
            emitter.complete();
            return;
        }

        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
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
                    saveMessage(user, chapter, MessageRole.USER, message, effectiveRequestId);
                    List<MangaAgentMessage> history = mangaAgentMessageRepository
                            .findByUserIdAndChapterIdOrderByCreatedAtAsc(user.getId(), chapterId);
                    List<AgentMessage> messages = buildMessages(chapter, user, history, message, effectiveRequestId);
                    sendRunEvent(run, emitter, AgentRunEvent.of("context_loading", "context", "正在同步故事知识"));
                    agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapterId, String.valueOf(user.getId()));
                    AgentRunRequest request = buildRunRequest(chapter, user, messages, deepseekApiKey, modelSpec);
                    StringBuilder reply = new StringBuilder();
                    AtomicBoolean finished = new AtomicBoolean(false);

                    try {
                        harnessAgentGateway.streamEvents(request)
                                .doOnNext(event -> agentScopeEventMapper.map(event).ifPresent(mapped -> {
                                    if ("text_delta".equals(mapped.type())) {
                                        reply.append(mapped.text());
                                    }
                                    sendRunEvent(run, emitter, mapped);
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
                            return fallbackAfterToolSuccess(user, chapter, effectiveRequestId, toolState, error);
                        }
                        saveFailureMessage(user, chapter, error, effectiveRequestId);
                        throw new BusinessException(502, "Agent service failed: " + error);
                    }

                    String finalReply = reply.toString().trim();
                    if (!finished.get() || finalReply.isBlank()) {
                        if (toolState.hasSuccessfulMutatingTool()) {
                            return fallbackAfterToolSuccess(user, chapter, effectiveRequestId, toolState, "Agent returned empty response");
                        }
                        throw new BusinessException(502, "Agent returned empty response");
                    }
                    saveMessage(user, chapter, MessageRole.ASSISTANT, finalReply, effectiveRequestId);
                    return Map.of("reply", finalReply);
                }
        );
        String reply = String.valueOf(result.getOrDefault("reply", ""));
        if (Boolean.TRUE.equals(result.get("agent_final_response_degraded"))) {
            mangaAgentRunService.markDegraded(effectiveRequestId, user.getId(), chapterId, reply,
                    "Agent final response degraded after tool success");
        } else {
            mangaAgentRunService.markSucceeded(effectiveRequestId, user.getId(), chapterId, reply);
        }
        sendDone(run, emitter, reply, effectiveRequestId);
        emitter.complete();
    }

    private Map<String, Object> runLeader(Long chapterId, String message, UUID effectiveRequestId, User user,
                                          String deepseekApiKey, AgentModelSpec modelSpec,
                                          AgentRunToolStatus.RunState toolState) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        saveMessage(user, chapter, MessageRole.USER, message, effectiveRequestId);
        List<MangaAgentMessage> history = mangaAgentMessageRepository
                .findByUserIdAndChapterIdOrderByCreatedAtAsc(user.getId(), chapterId);
        List<AgentMessage> messages = buildMessages(chapter, user, history, message, effectiveRequestId);
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(chapterId, String.valueOf(user.getId()));

        AgentRunRequest request = new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapterId,
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of(
                        "coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))
                ),
                modelSpec,
                deepseekApiKey
        );

        try {
            String reply = harnessAgentGateway.generateText(request).block(agentRunTimeout());
            throwIfWaitingForUser(toolState);
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            saveMessage(user, chapter, MessageRole.ASSISTANT, reply, effectiveRequestId);
            return Map.of("reply", reply);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(toolState);
            throw new BusinessException(502, "Agent tool suspended without user input");
        } catch (BusinessException e) {
            if (toolState.hasSuccessfulMutatingTool()) {
                return fallbackAfterToolSuccess(user, chapter, effectiveRequestId, toolState, e.getMessage());
            }
            saveFailureMessage(user, chapter, e.getMessage(), effectiveRequestId);
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (toolState.hasSuccessfulMutatingTool()) {
                return fallbackAfterToolSuccess(user, chapter, effectiveRequestId, toolState, error);
            }
            saveFailureMessage(user, chapter, error, effectiveRequestId);
            throw new BusinessException(502, "Agent service failed: " + error);
        }
    }

    private void throwIfWaitingForUser(AgentRunToolStatus.RunState toolState) {
        AgentUserInputRequest waiting = toolState.userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    private AgentRunRequest buildRunRequest(Chapter chapter, User user, List<AgentMessage> messages,
                                            String deepseekApiKey, AgentModelSpec modelSpec) {
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of(
                        "coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))
                ),
                modelSpec,
                deepseekApiKey
        );
    }

    private Map<String, Object> fallbackAfterToolSuccess(User user, Chapter chapter, UUID requestId,
                                                         AgentRunToolStatus.RunState toolState, String error) {
        String reply = fallbackReply(chapter, toolState, error);
        saveMessage(user, chapter, MessageRole.ASSISTANT, reply, requestId);
        saveMessage(user, chapter, MessageRole.SYSTEM, fallbackFailureContent(error, toolState), requestId);
        return Map.of(
                "reply", reply,
                "agent_final_response_degraded", true
        );
    }

    static List<AgentMessage> buildMessages(Chapter chapter, User user, List<MangaAgentMessage> history,
                                            String currentMessage, UUID currentRequestId) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new AgentMessage("system", buildSystemPrompt(chapter, user)));
        List<MangaAgentMessage> visibleHistory = history.stream()
                .filter(item -> item.getRole() == MessageRole.USER || item.getRole() == MessageRole.ASSISTANT)
                .filter(item -> !currentRequestId.equals(item.getRequestId()))
                .toList();
        visibleHistory.stream()
                .skip(Math.max(0, visibleHistory.size() - HISTORY_LIMIT_FOR_AGENT))
                .forEach(item -> messages.add(new AgentMessage(item.getRole().name().toLowerCase(), item.getContent())));
        messages.add(new AgentMessage("user", currentMessage));
        return messages;
    }

    @Transactional
    protected void saveMessage(User user, Chapter chapter, MessageRole role, String content, UUID requestId) {
        if (mangaAgentMessageRepository.findByUserIdAndRequestIdAndRole(user.getId(), requestId, role).isPresent()) {
            return;
        }
        MangaAgentMessage message = new MangaAgentMessage();
        message.setUser(user);
        message.setStory(chapter.getStory());
        message.setChapter(chapter);
        message.setRole(role);
        message.setContent(content);
        message.setRequestId(requestId);
        mangaAgentMessageRepository.save(message);
    }

    @Transactional
    protected void saveFailureMessage(User user, Chapter chapter, String error, UUID requestId) {
        saveMessage(user, chapter, MessageRole.SYSTEM, failureContent(error), requestId);
    }

    private Duration agentRunTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getRunTimeoutSeconds()));
    }

    private String failureContent(String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "agent_run_failed");
        payload.put("message", error == null || error.isBlank() ? "unknown error" : error);
        return payload.toString();
    }

    private String fallbackFailureContent(String error, AgentRunToolStatus.RunState toolState) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "agent_run_degraded_after_tool_success");
        payload.put("message", error == null || error.isBlank() ? "unknown error" : error);
        AgentRunToolStatus.ToolEvent event = toolState.lastSuccessfulMutatingEvent();
        if (event != null) {
            payload.put("tool", event.toolName());
            payload.put("scenes_count", event.result().getOrDefault("scenes_count", ""));
        }
        return payload.toString();
    }

    private String fallbackReply(Chapter chapter, AgentRunToolStatus.RunState toolState, String error) {
        AgentRunToolStatus.ToolEvent event = toolState.lastSuccessfulMutatingEvent();
        Object scenesCount = event == null ? chapter.getImageCount() : event.result().getOrDefault("scenes_count", chapter.getImageCount());
        String action = switch (event == null ? "" : event.toolName()) {
            case "generate_storyboard" -> "分镜已生成并保存";
            case "save_storyboard", "save_structured_storyboard" -> "分镜已重写并保存";
            default -> "本次修改已保存";
        };
        return """
                %s到当前章节，共 %s 页。

                智能体已经完成了关键保存动作，但最终总结回复没有及时完成。你可以刷新分镜查看结果，继续让我润色，或点击 Generate Manga 继续生成图片。
                """.formatted(action, scenesCount).trim();
    }

    private Map<String, Object> toolEventPayload(AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", event.toolName());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        if (event.error() != null && !event.error().isBlank()) {
            payload.put("error", event.error());
        }
        Object saved = event.result().get("saved");
        if (saved != null) {
            payload.put("saved", saved);
        }
        Object scenesCount = event.result().get("scenes_count");
        if (scenesCount != null) {
            payload.put("scenes_count", scenesCount);
        }
        return payload;
    }

    private void sendStatus(MangaAgentRun run, SseEmitter emitter, String message, UUID requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("requestId", requestId);
        appendRunEvent(run, "status", payload);
        sendSse(emitter, "status", payload);
    }

    private void sendToolEvent(MangaAgentRun run, SseEmitter emitter, AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = toolEventPayload(event);
        appendRunEvent(run, "tool", payload);
        sendSse(emitter, "tool", payload);
    }

    private void sendRunEvent(MangaAgentRun run, SseEmitter emitter, AgentRunEvent event) {
        Map<String, Object> payload = mangaAgentRunService.toPayload(event);
        if (!"text_delta".equals(event.type())) {
            appendRunEvent(run, "run_event", payload);
        }
        sendSse(emitter, "run_event", payload);
    }

    private void sendUserInputRequested(MangaAgentRun run, SseEmitter emitter, UUID requestId,
                                        AgentUserInputRequest request) {
        Map<String, Object> payload = userInputPayload(requestId, request);
        appendRunEvent(run, "user_input_requested", payload);
        sendSse(emitter, "user_input_requested", payload);
    }

    private void sendUserAnswerEvent(MangaAgentRun run, SseEmitter emitter, UUID requestId, String answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "user_answered");
        payload.put("phase", "human_input");
        payload.put("label", "已收到用户选择");
        payload.put("status", "success");
        payload.put("requestId", requestId);
        payload.put("answer", answer == null || answer.isBlank() ? "继续默认方案" : answer.trim());
        payload.put("createdAt", java.time.OffsetDateTime.now().toString());
        appendRunEvent(run, "run_event", payload);
        sendSse(emitter, "run_event", payload);
    }

    private void sendDone(MangaAgentRun run, SseEmitter emitter, String reply, UUID requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reply", reply);
        payload.put("requestId", requestId);
        appendRunEvent(run, "done", payload);
        sendSse(emitter, "done", payload);
    }

    private void sendError(MangaAgentRun run, SseEmitter emitter, UUID requestId, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detail", detail);
        payload.put("requestId", requestId);
        appendRunEvent(run, "error", payload);
        sendSse(emitter, "error", payload);
    }

    private Map<String, Object> userInputPayload(UUID requestId, AgentUserInputRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("question", request.question());
        payload.put("options", request.options());
        payload.put("allowFreeText", request.allowFreeText());
        payload.put("reason", request.reason());
        return payload;
    }

    private void appendRunEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        if (run == null) {
            return;
        }
        try {
            mangaAgentRunService.appendEvent(run, eventName, payload);
        } catch (Exception e) {
            log.debug("Failed to persist manga agent run event {}: {}", eventName, e.getMessage());
        }
    }

    private void sendSse(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("Failed to send manga agent SSE {}: {}", eventName, e.getMessage());
        }
    }

    private String resumeMessage(String originalInput, AgentUserInputRequest waiting, String answer) {
        String selected = answer == null || answer.isBlank() ? "继续默认方案" : answer.trim();
        String question = waiting == null ? "" : waiting.question();
        return """
                继续之前暂停的漫画智能体任务。

                原始用户任务：
                %s

                暂停时需要用户决策：
                %s

                用户选择：
                %s

                请基于用户选择继续完成原始任务，不要重复询问同一个问题。
                """.formatted(
                originalInput == null || originalInput.isBlank() ? "继续当前漫画创作任务" : originalInput.trim(),
                question == null || question.isBlank() ? "未记录具体问题" : question.trim(),
                selected
        ).trim();
    }

    private static String buildSystemPrompt(Chapter chapter, User user) {
        return """
                You are ArtVerse Manga Director, an AI workflow assistant for Chinese AI manga creation.
                Always answer in concise Chinese.
                When editing or generating storyboard scenes, write in a way that can be used directly as a manga page production script.
                Do not output poster-like single image descriptions.
                Do not use English, traditional Chinese, or mixed-language dialogue in storyboard content.
                Prefer scene rhythm, panel sequencing, character continuity, and short Chinese dialogue.

                Current user id: %s
                Current story title: %s
                Current display chapter number: %s
                Current display chapter name: 第%s话

                The selected story and chapter in the left workspace are the only trusted target context.
                If the user mentions another chapter, do not silently switch. Ask the user to switch the workspace first.
                Never use any database id as a visible chapter number. When speaking to the user, only use the current display chapter name.

                You can use tools to inspect chapter context, generate storyboard scenes, save edited storyboard scenes, and ask the user for a decision.
                Prefer save_structured_storyboard when creating or rewriting storyboard pages: provide pages with 4-6 panels each, using fields like shot, description, dialogue, narration, and sfx.
                Use save_storyboard only when you already have a complete validated text scene list.
                Use ask_user instead of plain text questions when a decision blocks progress, such as choosing between incompatible workflow options, resolving conflicting story direction, choosing whether to overwrite existing storyboard scenes, or deciding how to handle mismatched page counts.
                Rules:
                - First inspect chapter context when the user asks about the manga workflow.
                - Confirm the current chapter in your response before taking costly actions.
                - If source content is missing, tell the user to write chat content or import novel text first.
                - If storyboard scenes are missing and the user asks to continue, generate storyboard scenes.
                - Do not directly claim that images have been generated. Image generation is a long-running SSE task handled by the existing Generate Manga action.
                - After storyboard is ready, clearly tell the user that they can click Generate Manga, or ask you to refine scenes.
                - Keep business actions explicit and summarize what changed.
                """.formatted(
                user.getId(),
                chapter.getStory().getTitle(),
                chapter.getChapterNumber(),
                chapter.getChapterNumber()
        );
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record RunResult(String reply, UUID requestId) {
    }
}
