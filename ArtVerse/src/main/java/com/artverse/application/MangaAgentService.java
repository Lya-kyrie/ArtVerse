package com.artverse.application;

import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.application.workflow.MangaWorkflowOrchestrator;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
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
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class MangaAgentService {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final MangaAgentConversationRegistry mangaAgentConversationRegistry;
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
    private final MangaWorkflowOrchestrator mangaWorkflowOrchestrator;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return mangaAgentConversationService.listMessages(conversation);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> listConversations(Long chapterId, User user) {
        return mangaAgentConversationRegistry.list(chapterId, user);
    }

    public MangaAgentConversation createConversation(Long chapterId, User user) {
        return mangaAgentConversationRegistry.create(chapterId, user);
    }

    public MangaAgentConversation archiveConversation(Long chapterId, UUID conversationId, User user) {
        return mangaAgentConversationRegistry.archive(chapterId, user, conversationId);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return mangaAgentConversationService.listMessages(conversation);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return run(conversation, message, requestId, user);
    }

    public RunResult run(MangaAgentConversation conversation, String message, UUID requestId, User user) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        try (AgentRunToolStatus.RunScope scope = agentRunToolStatus.start(
                user.getId(),
                conversation.getChapter().getId(),
                effectiveRequestId
        )) {
            return new RunResult(
                    String.valueOf(mangaWorkflowOrchestrator.runWithToolState(conversation, message, effectiveRequestId, scope.state())
                            .getOrDefault("reply", "")),
                    effectiveRequestId
            );
        }
    }

    public SseEmitter runStream(Long chapterId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return runStreamInternal(conversation, message, requestId, MangaAgentRunEventPublisher.StreamProtocol.LEGACY_AND_AG_UI);
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return runStreamInternal(conversation, message, requestId, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message, UUID requestId, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return runStreamInternal(conversation, message, requestId, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    private SseEmitter runStreamInternal(MangaAgentConversation conversation, String message, UUID requestId,
                                         MangaAgentRunEventPublisher.StreamProtocol protocol) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = sinkFor(emitter, protocol);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    effectiveRequestId,
                    event -> sink.sendToolEvent(runRef.get(), event)
            )) {
                mangaWorkflowOrchestrator.runStreamLeader(conversation, message, effectiveRequestId, ignored.state(), sink, runRef);
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
            }
        });

        return emitter;
    }

    public SseEmitter resumeStream(Long chapterId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, MangaAgentRunEventPublisher.StreamProtocol.LEGACY_AND_AG_UI);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return resumeStreamInternal(conversation, requestId, answer, MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY);
    }

    private SseEmitter resumeStreamInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                            MangaAgentRunEventPublisher.StreamProtocol protocol) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(conversation, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        String message = mangaAgentConversationService.resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(conversation, requestId);

        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = sinkFor(emitter, protocol);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>(waitingRun);
        executor.submit(() -> {
            try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                    user.getId(),
                    chapterId,
                    requestId,
                    event -> sink.sendToolEvent(runRef.get(), event)
            )) {
                sink.sendUserAnswerEvent(waitingRun, requestId, answer);
                mangaWorkflowOrchestrator.runStreamLeader(conversation, message, requestId, ignored.state(), sink, runRef);
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
            }
        });
        return emitter;
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user) {
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return resume(conversation, requestId, answer);
    }

    public RunResult resume(MangaAgentConversation conversation, UUID requestId, String answer) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        MangaAgentRun waitingRun = mangaAgentRunService.requireWaitingRun(conversation, requestId);
        AgentUserInputRequest waiting = mangaAgentRunService.waitingInput(waitingRun);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        mangaAgentRunService.markRunning(conversation, requestId);
        String message = mangaAgentConversationService.resumeMessage(waitingRun.getInputMessage(), waiting, answer);
        try {
            RunResult result = run(conversation, message, requestId, user);
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
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public Optional<MangaAgentRunService.RunSnapshot> latestOpenRun(Long chapterId, UUID conversationId, User user) {
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot getRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        interruptStaleRunningRuns();
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        MangaAgentConversation conversation = mangaAgentConversationRegistry.activeOrCreate(chapterId, user);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        return mangaAgentRunService.snapshot(run);
    }

    public MangaAgentRunService.RunSnapshot cancelRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentConversation conversation = mangaAgentConversationRegistry.require(chapterId, user, conversationId);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        return mangaAgentRunService.snapshot(run);
    }

    private void interruptStaleRunningRuns() {
        int staleSeconds = Math.max(
                properties.getAgent().getStaleRunningSeconds(),
                properties.getAgent().getRunTimeoutSeconds() * 2
        );
        mangaAgentRunService.interruptStaleRunningRuns(OffsetDateTime.now().minusSeconds(staleSeconds));
    }

    private MangaAgentRunEventPublisher.RunEventSink sinkFor(SseEmitter emitter,
                                                             MangaAgentRunEventPublisher.StreamProtocol protocol) {
        return protocol == MangaAgentRunEventPublisher.StreamProtocol.AG_UI_ONLY
                ? mangaAgentRunEventPublisher.agUiOnly(emitter)
                : mangaAgentRunEventPublisher.legacyAndAgUi(emitter);
    }

    public record RunResult(String reply, UUID requestId) {
    }
}
