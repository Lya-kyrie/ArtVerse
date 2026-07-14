package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaReviewMetrics;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaAgentExecutionSupportTest {

    private MangaAgentConversationService conversationService;
    private AgentScopeHarnessAgentGateway gateway;
    private MangaAgentExecutionSupport support;
    private MangaWorkflowExecutionContext context;
    private AgentRunToolStatus.RunState toolState;
    private AgentRunRequest request;
    private MangaAgentConversation conversation;
    private UUID requestId;
    private MangaReviewMetrics reviewMetrics;

    @BeforeEach
    void setUp() {
        conversationService = mock(MangaAgentConversationService.class);
        gateway = mock(AgentScopeHarnessAgentGateway.class);
        reviewMetrics = mock(MangaReviewMetrics.class);
        support = new MangaAgentExecutionSupport(
                conversationService,
                mock(AgentWorkspaceSyncService.class),
                mock(ApiKeyService.class),
                gateway,
                new ArtVerseProperties(),
                reviewMetrics
        );
        context = mock(MangaWorkflowExecutionContext.class);
        toolState = mock(AgentRunToolStatus.RunState.class);
        request = mock(AgentRunRequest.class);
        conversation = mock(MangaAgentConversation.class);
        requestId = UUID.randomUUID();

        when(context.toolState()).thenReturn(toolState);
        when(context.persistConversationMessages()).thenReturn(true);
        when(context.conversation()).thenReturn(conversation);
        when(context.requestId()).thenReturn(requestId);
    }

    @Test
    void executeRequestPersistsSuccessfulReply() {
        when(gateway.generateText(request)).thenReturn(Mono.just("finished"));

        MangaWorkflowResult result = support.executeRequest(context, request, false);

        assertThat(result.reply()).isEqualTo("finished");
        assertThat(result.degraded()).isFalse();
        verify(conversationService).saveMessage(
                conversation, MessageRole.ASSISTANT, "finished", requestId);
    }

    @Test
    void executeRequestPreservesBusinessExceptionAndPersistsFailure() {
        BusinessException failure = new BusinessException(409, "conflict");
        when(gateway.generateText(request)).thenReturn(Mono.error(failure));

        assertThatThrownBy(() -> support.executeRequest(context, request, false))
                .isSameAs(failure);
        verify(conversationService).saveFailureMessage(conversation, "conflict", requestId);
    }

    @Test
    void executeRequestPersistsEmptyResponseFailure() {
        when(gateway.generateText(request)).thenReturn(Mono.just("   "));

        assertThatThrownBy(() -> support.executeRequest(context, request, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent returned empty response");
        verify(conversationService).saveFailureMessage(
                conversation, "Agent returned empty response", requestId);
    }

    @Test
    void executeRequestDegradesAfterSuccessfulMutatingTool() {
        when(gateway.generateText(request)).thenReturn(Mono.error(new IllegalStateException("model failed")));
        when(toolState.hasSuccessfulMutatingTool()).thenReturn(true);
        when(conversationService.fallbackAfterToolSuccess(
                conversation, requestId, toolState, "model failed", true))
                .thenReturn(Map.of(
                        "reply", "Storyboard was saved",
                        "agent_final_response_degraded", true
                ));

        MangaWorkflowResult result = support.executeRequest(context, request, true);

        assertThat(result.reply()).isEqualTo("Storyboard was saved");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void reviewRequestRequiresAllDeclaredSubagentsToComplete() {
        when(request.taskType()).thenReturn(AgentTaskType.MANGA_REVIEW);
        when(gateway.streamEvents(request)).thenReturn(Flux.fromIterable(reviewEvents(true)));

        MangaWorkflowResult result = support.executeRequest(context, request, false);

        assertThat(result.reply()).isEqualTo("review complete");
        assertThat(result.degraded()).isFalse();
        assertThat(result.attributes()).containsEntry("review_subagents_started", 4)
                .containsEntry("review_subagents_completed", 4);
        verify(reviewMetrics).recordStarted(4);
        verify(reviewMetrics).recordCompleted(4);
    }

    @Test
    void reviewRequestDegradesWhenADeclaredSubagentIsMissing() {
        when(request.taskType()).thenReturn(AgentTaskType.MANGA_REVIEW);
        when(gateway.streamEvents(request)).thenReturn(Flux.fromIterable(reviewEvents(false)));

        MangaWorkflowResult result = support.executeRequest(context, request, false);

        assertThat(result.degraded()).isTrue();
        assertThat(result.attributes()).containsEntry("review_subagents_started", 3)
                .containsEntry("review_subagents_completed", 3);
        assertThat(result.attributes().get("review_subagents_missing"))
                .isEqualTo(java.util.List.of("continuity-reviewer"));
        verify(reviewMetrics).recordMissing(1);
    }

    private java.util.List<AgentEvent> reviewEvents(boolean includeContinuity) {
        java.util.List<String> reviewers = new java.util.ArrayList<>(java.util.List.of(
                "camera-reviewer", "character-reviewer", "pacing-reviewer"));
        if (includeContinuity) reviewers.add("continuity-reviewer");
        java.util.List<AgentEvent> events = new java.util.ArrayList<>();
        for (String reviewer : reviewers) {
            events.add(new AgentStartEvent("event", reviewer, "session", "reply", reviewer, "assistant"));
        }
        for (String reviewer : reviewers) {
            events.add(new AgentEndEvent("reply").withSource(reviewer));
        }
        events.add(new TextBlockDeltaEvent("event", "manga-review", "reply", "block", "review complete"));
        return events;
    }
}
