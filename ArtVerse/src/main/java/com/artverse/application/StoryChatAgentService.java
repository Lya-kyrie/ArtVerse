package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ChatMessageCompletionStatus;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.guard.AgentConcurrencyGate;
import com.artverse.persistence.ChatMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class StoryChatAgentService {

    private final AiConversationService conversationService;
    private final MangaAgentRunService runService;
    private final MangaAgentRunEventPublisher eventPublisher;
    private final AgentRunToolStatus toolStatus;
    private final StoryChatRouter router;
    private final StoryChatRuntimeContextAssembler contextAssembler;
    private final AgentScopeHarnessAgentGateway gateway;
    private final AgentModelSpecFactory modelSpecFactory;
    private final ArtVerseSkillRegistry skillRegistry;
    private final NovelBusinessSkillRouter skillRouter;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentConcurrencyGate concurrencyGate;
    private final NovelContentArtifactService artifactService;
    private final AgentScopeHarnessAgentGateway agentScopeGateway;
    private final ObjectMapper objectMapper;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(Long chapterId, User user) {
        MangaAgentConversation conversation = conversationService.storyConversation(user, chapterId);
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message,
                                    UUID requestId, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        conversationService.autoTitle(conversation, message);
        conversationService.touch(conversation);
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = eventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();
        submit(user.getId(), effectiveRequestId, () -> runTask(() ->
                execute(conversation, message, effectiveRequestId, llmConfig, sink, runRef, false),
                conversation, effectiveRequestId, sink, runRef));
        return emitter;
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId,
                                       String decision, UUID artifactId, String answer,
                                       User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = eventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();
        submit(user.getId(), requestId, () -> runTask(() -> {
            MangaAgentRunService.RunSnapshot snapshot = requireWaitingSnapshot(conversation, requestId);
            UUID effectiveArtifactId = artifactId == null ? artifactFromWaiting(snapshot) : artifactId;
            if ("discard".equalsIgnoreCase(decision)) {
                artifactService.reject(user.getId(), chapterId, requestId, effectiveArtifactId);
                String reply = "已放弃本次小说原文草稿，章节原文未改变。";
                saveAssistantMessage(conversation, reply, requestId, "{}");
                MangaAgentRun run = runService.findRun(conversation, requestId)
                        .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
                runRef.set(run);
                runService.markSucceeded(conversation, requestId, reply);
                sink.sendDone(run, reply, requestId);
                return;
            }
            if (!"confirm".equalsIgnoreCase(decision)) {
                throw new BusinessException(400, "decision must be confirm or discard");
            }
            toolStatus.authorizeMutationArtifact(user.getId(), chapterId, requestId, effectiveArtifactId);
            String resumeMessage = "用户已确认写入小说原文 artifact_id=" + effectiveArtifactId
                    + "。现在调用 commit_novel_content 提交该 artifact，并只在工具成功后声明已保存。";
            execute(conversation, resumeMessage, requestId, llmConfig, sink, runRef, true);
        }, conversation, requestId, sink, runRef));
        return emitter;
    }

    public OptionalRun latestOpenRun(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        return new OptionalRun(runService.findLatestOpenRun(conversation).map(runService::snapshot).orElse(null));
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        return runService.findRun(conversation, requestId)
                .map(runService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        MangaAgentRun run = runService.cancel(conversation, requestId, "Story chat run cancelled by user");
        toolStatus.markCancelled(user.getId(), chapterId, requestId);
        toolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        agentScopeGateway.interrupt(requestId);
        return runService.snapshot(run);
    }

    public List<NovelContentArtifactService.ArtifactView> artifacts(Long chapterId, UUID conversationId,
                                                                    UUID requestId, User user) {
        requireConversation(chapterId, conversationId, user);
        return artifactService.list(user.getId(), chapterId, requestId);
    }

    private void execute(MangaAgentConversation conversation, String message, UUID requestId,
                         UserProviderConfig llmConfig, MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef, boolean resume) {
        StoryChatRoute route = resume ? StoryChatRoute.WRITE : router.classify(message);
        MangaAgentRun run = runService.startOrReuse(conversation, requestId, message,
                AgentRunType.STORY_CHAT, com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION, route.name());
        runRef.set(run);
        sink.sendStatus(run, "小说对话智能体正在处理章节。", requestId);
        saveUserMessage(conversation, message, requestId);
        try (AgentRunToolStatus.RunScope scope = toolStatus.start(
                conversation.getUser().getId(), conversation.getChapter().getId(), requestId,
                event -> {
                    MangaAgentRun active = runRef.get();
                    sink.recordToolProgress(active);
                    sink.sendToolEvent(active, event);
                })) {
            List<AgentMessage> messages = contextAssembler.assemble(
                    conversation, message, route, conversation.getUser().getId());
            AgentRunRequest request = agentRequest(conversation, requestId, route, messages, llmConfig, run);
            StringBuilder reply = new StringBuilder();
            for (AgentEvent event : gateway.streamEvents(request).toIterable()) {
                if (scope.state().isCancelled()) {
                    throw new BusinessException(409, "Story chat run was cancelled");
                }
                sink.recordProgress(runRef.get(), "MODEL");
                Optional<AgentRunEvent> mapped = mapAgentScopeEvent(event);
                mapped.ifPresent(agentRunEvent -> {
                    sink.sendRunEvent(runRef.get(), agentRunEvent);
                    if ("text_delta".equals(agentRunEvent.type()) && agentRunEvent.text() != null) {
                        reply.append(agentRunEvent.text());
                    }
                });
            }
            if (scope.state().userInputRequest() != null) {
                throw new AgentUserInputRequiredException(scope.state().userInputRequest());
            }
            String finalReply = reply.toString().trim();
            if (finalReply.isBlank() && route == StoryChatRoute.WRITE
                    && scope.state().lastSuccessfulMutatingEvent() != null) {
                finalReply = "小说原文已保存。";
            }
            if (finalReply.isBlank()) {
                finalReply = "本次小说对话已完成。";
            }
            saveAssistantMessage(conversation, finalReply, requestId,
                    writeSkillVersions(request.businessSkillSelection()));
            runService.markSucceeded(conversation, requestId, finalReply);
            sink.sendDone(runRef.get(), finalReply, requestId);
        }
    }

    private AgentRunRequest agentRequest(MangaAgentConversation conversation, UUID requestId, StoryChatRoute route,
                                         List<AgentMessage> messages, UserProviderConfig llmConfig,
                                         MangaAgentRun run) {
        AgentTaskType taskType = route == StoryChatRoute.WRITE
                ? AgentTaskType.STORY_CHAT_WRITE
                : AgentTaskType.STORY_CHAT_READ;
        String latestUser = messages.stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .reduce((left, right) -> right)
                .map(AgentMessage::content)
                .orElse("");
        BusinessSkillSelection selection = skillRegistry.selectionForNovelMode(
                skillRouter.classify(latestUser).mode());
        return new AgentRunRequest(
                String.valueOf(conversation.getUser().getId()),
                conversation.getStory().getId(),
                conversation.getChapter().getId(),
                taskType,
                messages,
                Map.of("fencing_token", run.getFencingToken(), "step_id", "story-chat:" + route.name()),
                modelSpecFactory.fromProviderConfig(llmConfig),
                llmConfig.apiKey(),
                requestId,
                conversation.getConversationUuid(),
                selection
        );
    }

    private void runTask(Runnable task, MangaAgentConversation conversation, UUID requestId,
                         MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef) {
        try {
            task.run();
        } catch (AgentUserInputRequiredException e) {
            MangaAgentRun run = runRef.get();
            if (run != null) {
                runService.markWaiting(conversation, requestId, e.request());
            }
            sink.sendUserInputRequested(run, requestId, e.request());
        } catch (Exception error) {
            String detail = error.getMessage() == null ? "Story chat agent request failed" : error.getMessage();
            MangaAgentRun run = runRef.get();
            if (run != null && !runService.isTerminal(conversation, requestId)) {
                runService.markFailed(conversation, requestId, detail);
            }
            sink.sendError(run, requestId, detail);
        } finally {
            sink.complete();
        }
    }

    private void submit(Long userId, UUID requestId, Runnable task) {
        AgentConcurrencyGate.Permit permit = concurrencyGate.acquireOrReject(userId, requestId);
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    concurrencyGate.release(permit);
                }
            });
        } catch (RejectedExecutionException error) {
            concurrencyGate.release(permit);
            throw new BusinessException(503, "Failed to submit story chat task: system overloaded, please retry");
        }
    }

    private MangaAgentRunService.RunSnapshot requireWaitingSnapshot(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRunService.RunSnapshot snapshot = runService.findRun(conversation, requestId)
                .map(runService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "Can only resume a paused run");
        }
        return snapshot;
    }

    private UUID artifactFromWaiting(MangaAgentRunService.RunSnapshot snapshot) {
        String reason = snapshot.userInputRequest() == null ? "" : snapshot.userInputRequest().reason();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matcher(reason == null ? "" : reason);
        if (!matcher.find()) {
            throw new BusinessException(400, "artifact_id is required for story chat resume");
        }
        return UUID.fromString(matcher.group());
    }

    private MangaAgentConversation requireConversation(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = conversationService.storyConversation(user, chapterId);
        if (conversationId != null && !conversation.getConversationUuid().equals(conversationId)) {
            throw new BusinessException(404, "Story chat conversation not found");
        }
        return conversation;
    }

    @Transactional
    public ChatMessage saveUserMessage(MangaAgentConversation conversation, String content, UUID requestId) {
        return chatMessageRepository.findByConversationIdAndRequestIdAndRole(
                        conversation.getId(), requestId, MessageRole.USER)
                .orElseGet(() -> {
                    ChatMessage message = new ChatMessage();
                    message.setChapter(conversation.getChapter());
                    message.setConversation(conversation);
                    message.setRole(MessageRole.USER);
                    message.setContent(content);
                    message.setRequestId(requestId);
                    message.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
                    return chatMessageRepository.save(message);
                });
    }

    @Transactional
    public ChatMessage saveAssistantMessage(MangaAgentConversation conversation, String content,
                                            UUID requestId, String skillVersionsJson) {
        return chatMessageRepository.findByConversationIdAndRequestIdAndRole(
                        conversation.getId(), requestId, MessageRole.ASSISTANT)
                .orElseGet(() -> {
                    ChatMessage message = new ChatMessage();
                    message.setChapter(conversation.getChapter());
                    message.setConversation(conversation);
                    message.setRole(MessageRole.ASSISTANT);
                    message.setContent(content);
                    message.setRequestId(requestId);
                    message.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
                    message.setSkillVersionsJson(skillVersionsJson == null || skillVersionsJson.isBlank()
                            ? "{}" : skillVersionsJson);
                    return chatMessageRepository.save(message);
                });
    }

    private String writeSkillVersions(BusinessSkillSelection selection) {
        try {
            return objectMapper.writeValueAsString(selection.skillVersions());
        } catch (Exception error) {
            return "{}";
        }
    }

    private Optional<AgentRunEvent> mapAgentScopeEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            return Optional.of(new AgentRunEvent("run_started", "started", "story chat started",
                    null, "running", null, Map.of("agent", start.getName() == null ? "unknown" : start.getName()),
                    java.time.OffsetDateTime.now()));
        }
        if (event instanceof ModelCallStartEvent) {
            return Optional.of(AgentRunEvent.of("model_started", "thinking", "model is analyzing the chapter"));
        }
        if (event instanceof ModelCallEndEvent) {
            return Optional.of(AgentRunEvent.of("model_finished", "thinking", "model analysis complete"));
        }
        if (event instanceof RequireExternalExecutionEvent ext) {
            return Optional.of(new AgentRunEvent("external_exec_required", "waiting_input",
                    "waiting for user confirmation", null, "waiting", null,
                    Map.of("toolCalls", ext.getToolCalls()), java.time.OffsetDateTime.now()));
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool("tool_call_started", tool.getToolCallName(),
                    tool.getToolCallName(), "running", Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool("tool_call_ready", tool.getToolCallName(),
                    tool.getToolCallName(), "running", Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool("tool_started", tool.getToolCallName(),
                    tool.getToolCallName(), "running", Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool("tool_finished", tool.getToolCallName(),
                    tool.getToolCallName(), status, Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = text.getDelta();
            return delta == null || delta.isBlank() ? Optional.empty() : Optional.of(AgentRunEvent.text(delta));
        }
        if (event instanceof AgentResultEvent) {
            return Optional.of(AgentRunEvent.of("reply_ready", "replying", "final reply generated"));
        }
        if (event instanceof AgentEndEvent) {
            return Optional.of(AgentRunEvent.of("run_finished", "finished", "agent finished"));
        }
        return Optional.empty();
    }

    public record OptionalRun(MangaAgentRunService.RunSnapshot run) { }
}
