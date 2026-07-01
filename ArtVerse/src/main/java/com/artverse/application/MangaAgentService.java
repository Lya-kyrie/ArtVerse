package com.artverse.application;

import com.artverse.application.workflow.MangaWorkflowOrchestrator;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.User;
import com.artverse.guard.AgentConcurrencyGate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class MangaAgentService {

    private final MangaAgentConversationService conversationService;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaAgentRunEventPublisher mangaAgentRunEventPublisher;
    private final MangaWorkflowOrchestrator mangaWorkflowOrchestrator;
    private final AgentRunToolStatus agentRunToolStatus;
    private final ChapterAccessService chapterAccessService;
    private final ArtVerseProperties properties;
    private final AgentConcurrencyGate agentConcurrencyGate;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return conversationService.listMessages(conversation);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> listConversations(Long chapterId, User user) {
        return conversationService.listConversations(chapterId, user);
    }

    public MangaAgentConversation createConversation(Long chapterId, User user) {
        return conversationService.createConversation(chapterId, user);
    }

    public MangaAgentConversation archiveConversation(Long chapterId, UUID conversationId, User user) {
        return conversationService.archiveConversation(chapterId, user, conversationId);
    }

    public void deleteConversation(Long chapterId, UUID conversationId, User user) {
        conversationService.deleteConversation(chapterId, user, conversationId);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return conversationService.listMessages(conversation);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        return run(chapterId, message, requestId, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return runInternal(conversation, message, requestId, MangaWorkflowRoute.DIRECTOR, llmConfig);
    }

    public RunResult run(Long chapterId, UUID conversationId, String message, UUID requestId, User user) {
        return run(chapterId, conversationId, message, requestId, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult run(Long chapterId, UUID conversationId, String message, UUID requestId, User user,
                         UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return runInternal(conversation, message, requestId, MangaWorkflowRoute.DIRECTOR, llmConfig);
    }

    private RunResult runInternal(MangaAgentConversation conversation, String message, UUID requestId,
                                  MangaWorkflowRoute route, UserProviderConfig llmConfig) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        agentConcurrencyGate.acquireOrReject();
        try {
            try (AgentRunToolStatus.RunScope scope = agentRunToolStatus.start(
                    conversation.getUser().getId(),
                    conversation.getChapter().getId(),
                    effectiveRequestId
            )) {
                return new RunResult(
                        String.valueOf(mangaWorkflowOrchestrator.runWithToolState(
                                        conversation, message, effectiveRequestId, route, scope.state(), llmConfig)
                                .getOrDefault("reply", "")),
                        effectiveRequestId
                );
            }
        } finally {
            agentConcurrencyGate.release();
        }
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user) {
        return runAgUiStream(chapterId, message, requestId, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user,
                                    UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return runStreamInternal(conversation, message, requestId, MangaWorkflowRoute.DIRECTOR, llmConfig);
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message, UUID requestId, User user) {
        return runAgUiStream(chapterId, conversationId, message, requestId, user,
                mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message, UUID requestId, User user,
                                    UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return runStreamInternal(conversation, message, requestId, MangaWorkflowRoute.DIRECTOR, llmConfig);
    }

    private SseEmitter runStreamInternal(MangaAgentConversation conversation, String message, UUID requestId,
                                         MangaWorkflowRoute route, UserProviderConfig llmConfig) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = mangaAgentRunEventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        agentConcurrencyGate.acquireOrReject();
        try {
            executor.submit(() -> {
            try {
                try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                        user.getId(),
                        chapterId,
                        effectiveRequestId,
                        event -> sink.sendToolEvent(runRef.get(), event)
                )) {
                    mangaWorkflowOrchestrator.runStreamLeader(
                            conversation, message, effectiveRequestId, route, ignored.state(), sink, runRef, llmConfig);
                } catch (AgentUserInputRequiredException e) {
                    MangaAgentRun run = runRef.get();
                    if (run != null) {
                        mangaAgentRunService.markWaiting(conversation, effectiveRequestId, e.request());
                    }
                    sink.sendUserInputRequested(run, effectiveRequestId, e.request());
                } catch (Exception e) {
                    String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
                    MangaAgentRun run = runRef.get();
                    if (run != null && !mangaAgentRunService.isTerminal(conversation, effectiveRequestId)) {
                        mangaAgentRunService.markFailed(conversation, effectiveRequestId, detail);
                    }
                    sink.sendError(run, effectiveRequestId, detail);
                } finally {
                    sink.complete();
                }
            } finally {
                agentConcurrencyGate.release();
            }
        });
        } catch (RejectedExecutionException e) {
            agentConcurrencyGate.release();
            throw new BusinessException(503, "Failed to submit agent task: system overloaded, please retry", "agent");
        }

        return emitter;
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user) {
        return resumeAgUiStream(chapterId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user,
                                       UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, llmConfig);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        return resumeAgUiStream(chapterId, conversationId, requestId, answer, user,
                mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user,
                                       UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return resumeStreamInternal(conversation, requestId, answer, llmConfig);
    }

    private SseEmitter resumeStreamInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                            UserProviderConfig llmConfig) {
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = mangaAgentRunEventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        agentConcurrencyGate.acquireOrReject();
        try {
            executor.submit(() -> {
            try {
                try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                        user.getId(),
                        chapterId,
                        requestId,
                        event -> sink.sendToolEvent(runRef.get(), event)
                )) {
                    MangaAgentRunService.RunSnapshot snapshot = requireWaitingSnapshot(conversation, requestId);
                    mangaWorkflowOrchestrator.runStreamLeader(
                            conversation,
                            resumeMessage(snapshot, answer),
                            requestId,
                            snapshot.route(),
                            ignored.state(),
                            sink,
                            runRef,
                            llmConfig
                    );
                } catch (AgentUserInputRequiredException e) {
                    MangaAgentRun run = runRef.get();
                    if (run != null) {
                        mangaAgentRunService.markWaiting(conversation, requestId, e.request());
                    }
                    sink.sendUserInputRequested(run, requestId, e.request());
                } catch (Exception e) {
                    String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
                    MangaAgentRun run = runRef.get();
                    if (run != null && !mangaAgentRunService.isTerminal(conversation, requestId)) {
                        mangaAgentRunService.markFailed(conversation, requestId, detail);
                    }
                    sink.sendError(run, requestId, detail);
                } finally {
                    sink.complete();
                }
            } finally {
                agentConcurrencyGate.release();
            }
        });
        } catch (RejectedExecutionException e) {
            agentConcurrencyGate.release();
            throw new BusinessException(503, "Failed to submit agent task: system overloaded, please retry", "agent");
        }

        return emitter;
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user) {
        return resume(chapterId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return resumeInternal(conversation, requestId, answer, llmConfig);
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        return resume(chapterId, conversationId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer, User user,
                            UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return resumeInternal(conversation, requestId, answer, llmConfig);
    }

    private RunResult resumeInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                     UserProviderConfig llmConfig) {
        MangaAgentRunService.RunSnapshot snapshot = mangaAgentRunService.snapshot(
                mangaAgentRunService.findRun(conversation, requestId)
                        .orElseThrow(() -> new BusinessException(404, "Agent run not found"))
        );
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "Can only resume a paused run");
        }
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null) {
            throw new BusinessException(409, "No waiting user input request on the run");
        }
        String message = conversationService.resumeMessage("Continue", waiting, answer);
        try {
            RunResult result = runInternal(conversation, message, requestId, snapshot.route(), llmConfig);
            mangaAgentRunService.markSucceeded(conversation, requestId, result.reply());
            return result;
        } catch (AgentUserInputRequiredException e) {
            mangaAgentRunService.markWaiting(conversation, requestId, e.request());
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
            mangaAgentRunService.markFailed(conversation, requestId, detail);
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(detail, e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRunService.RunSnapshot> latestOpenRun(Long chapterId, User user) {
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public Optional<MangaAgentRunService.RunSnapshot> latestOpenRun(Long chapterId, UUID conversationId, User user) {
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        return mangaAgentRunService.snapshot(run);
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        return mangaAgentRunService.snapshot(run);
    }

    private MangaAgentRunService.RunSnapshot requireWaitingSnapshot(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRunService.RunSnapshot snapshot = mangaAgentRunService.snapshot(
                mangaAgentRunService.findRun(conversation, requestId)
                        .orElseThrow(() -> new BusinessException(404, "Agent run not found"))
        );
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "Can only resume a paused run");
        }
        return snapshot;
    }

    private String resumeMessage(MangaAgentRunService.RunSnapshot snapshot, String answer) {
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null) {
            throw new BusinessException(409, "No waiting user input request on the run");
        }
        return conversationService.resumeMessage("Continue", waiting, answer);
    }

    private void interruptStaleRunningRuns() {
        int staleSeconds = Math.max(
                properties.getAgent().getStaleRunningSeconds(),
                properties.getAgent().getRunTimeoutSeconds() * 2
        );
        mangaAgentRunService.interruptStaleRunningRuns(OffsetDateTime.now().minusSeconds(staleSeconds));
    }

    public record RunResult(String reply, UUID requestId) {
    }
}
