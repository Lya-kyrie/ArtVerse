package com.artverse.application.workflow;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResultFinalizerTest {

    @Test
    void persistsReplyOnlyAfterFactVerificationAndMarksRunVerified() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ExecutionFactVerifier facts = mock(ExecutionFactVerifier.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, facts);
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        MangaWorkflowResult candidate = MangaWorkflowResult.success("已完成");
        ExecutionFactVerifier.VerifiedFacts verified = new ExecutionFactVerifier.VerifiedFacts(
                "review.report.v1", Map.of("route", "REVIEW", "reply_present", true));
        when(facts.verify(conversation, requestId, MangaWorkflowRoute.REVIEW, candidate)).thenReturn(verified);

        MangaWorkflowResult result = finalizer.finalizeResult(
                conversation, requestId, MangaWorkflowRoute.REVIEW, candidate);

        assertThat(result.attributes()).containsEntry("result_verification_status", "PASSED")
                .containsEntry("result_schema", "review.report.v1")
                .containsEntry("verified_result", verified.facts());
        var order = inOrder(runs, facts, conversations);
        order.verify(runs).requireFinalizable(conversation, requestId);
        order.verify(facts).verify(conversation, requestId, MangaWorkflowRoute.REVIEW, candidate);
        order.verify(conversations).saveMessage(conversation, MessageRole.ASSISTANT, "已完成", requestId);
        order.verify(runs).completeVerified(conversation, requestId, "已完成", false,
                "review.report.v1", verified.facts());
    }

    @Test
    void doesNotPersistCandidateReplyWhenFactVerificationFails() {
        MangaAgentConversationService conversations = mock(MangaAgentConversationService.class);
        MangaAgentRunService runs = mock(MangaAgentRunService.class);
        ExecutionFactVerifier facts = mock(ExecutionFactVerifier.class);
        ResultFinalizer finalizer = new ResultFinalizer(conversations, runs, facts);
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        UUID requestId = UUID.randomUUID();
        MangaWorkflowResult candidate = MangaWorkflowResult.success("已完成");
        BusinessException failure = new BusinessException(409, "No committed storyboard artifact exists for this run");
        when(facts.verify(conversation, requestId, MangaWorkflowRoute.STORYBOARD, candidate)).thenThrow(failure);

        assertThatThrownBy(() -> finalizer.finalizeResult(
                conversation, requestId, MangaWorkflowRoute.STORYBOARD, candidate)).isSameAs(failure);

        verify(conversations, never()).saveMessage(conversation, MessageRole.ASSISTANT, "已完成", requestId);
        verify(runs, never()).completeVerified(conversation, requestId, "已完成", false,
                "storyboard.outcome.v1", Map.of());
    }
}
