package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentModelSpec;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

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
        sendSse(emitter, "status", Map.of(
                "message", "智能体已开始处理当前章节",
                "requestId", effectiveRequestId
        ));

        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    effectiveRequestId,
                    event -> sendSse(emitter, "tool", toolEventPayload(event))
            )) {
                RunResult result = runWithToolState(chapterId, message, effectiveRequestId, user, ignored.state());
                sendSse(emitter, "done", Map.of(
                        "reply", result.reply(),
                        "requestId", result.requestId()
                ));
                emitter.complete();
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "智能体请求失败" : e.getMessage();
                sendSse(emitter, "error", Map.of(
                        "detail", detail,
                        "requestId", effectiveRequestId
                ));
                emitter.complete();
            }
        });

        return emitter;
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
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            saveMessage(user, chapter, MessageRole.ASSISTANT, reply, effectiveRequestId);
            return Map.of("reply", reply);
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

    private void sendSse(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("Failed to send manga agent SSE {}: {}", eventName, e.getMessage());
        }
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

                You can use tools to inspect chapter context, generate storyboard scenes, and save edited storyboard scenes.
                Prefer save_structured_storyboard when creating or rewriting storyboard pages: provide pages with 4-6 panels each, using fields like shot, description, dialogue, narration, and sfx.
                Use save_storyboard only when you already have a complete validated text scene list.
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
