package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.ExecutionPlanValidator;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowNodeRegistry;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MangaDirectorAgentNodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void composesStepsThroughTypedResultContract() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler storyboard = mock(MangaWorkflowNodeHandler.class);
        MangaWorkflowNodeHandler review = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());

        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("multi"),
                true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);

        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(storyboard.run(any())).thenReturn(MangaWorkflowResult.degraded(
                "Storyboard user reply", "Storyboard contract summary", "Storyboard handoff context"));
        when(review.run(any())).thenReturn(MangaWorkflowResult.success(
                "Review user reply", "Review contract summary", "Review handoff context"));

        MangaWorkflowResult result = node.run(TestContexts.context(run.getRequestId(), run));

        assertThat(result.reply())
                .contains("Storyboard contract summary")
                .contains("Review contract summary");
        assertThat(result.degraded()).isTrue();
        ArgumentCaptor<MangaWorkflowExecutionContext> contextCaptor = forClass(MangaWorkflowExecutionContext.class);
        verify(review).run(contextCaptor.capture());
        assertThat(contextCaptor.getValue().message()).contains("Storyboard handoff context");
        verify(support).saveReply(any(), org.mockito.ArgumentMatchers.contains("Review contract summary"));
    }

    @Test
    void streamingDirectorUsesChildStreamsWithStepContext() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler storyboard = mock(MangaWorkflowNodeHandler.class);
        MangaWorkflowNodeHandler review = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(requestId);
        run.setStatus(MangaAgentRunStatus.RUNNING);
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.95, List.of("write", "review"), true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION,
                java.util.Set.of(com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_WRITE,
                        com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_REVIEW));
        when(runService.findRun(1L, 7L, requestId)).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(review);
        when(storyboard.stream(any(), any())).thenReturn(MangaWorkflowResult.success("storyboard"));
        when(review.stream(any(), any())).thenReturn(MangaWorkflowResult.success("review"));
        MangaAgentRunEventPublisher.RunEventSink sink = mock(MangaAgentRunEventPublisher.RunEventSink.class);

        node.stream(TestContexts.context(requestId, run), new MangaWorkflowStreamContext(run, sink));

        ArgumentCaptor<MangaWorkflowStreamContext> streamCaptor = forClass(MangaWorkflowStreamContext.class);
        verify(storyboard).stream(any(), streamCaptor.capture());
        assertThat(streamCaptor.getValue().suppressTextDeltas()).isTrue();
        assertThat(streamCaptor.getValue().eventContext())
                .containsEntry("step", 0)
                .containsEntry("route", "STORYBOARD")
                .containsEntry("agentName", "storyboard-agent");
        verify(storyboard, never()).run(any());
        verify(review, never()).run(any());
    }

    @Test
    void executionPlanRoundTripsWithStepState() throws Exception {
        com.artverse.application.workflow.ExecutionStep step =
                new com.artverse.application.workflow.ExecutionStep(0, MangaWorkflowRoute.REVIEW, "review", false);
        step.markRunning("input");
        step.markCompleted("summary", "full handoff");
        com.artverse.application.workflow.ExecutionPlan plan = new com.artverse.application.workflow.ExecutionPlan(
                "plan-1", List.of(step), RoutingDecision.CURRENT_VERSION, java.time.OffsetDateTime.now());

        String json = objectMapper.writeValueAsString(plan);
        com.artverse.application.workflow.ExecutionPlan restored =
                objectMapper.readValue(json, com.artverse.application.workflow.ExecutionPlan.class);

        assertThat(restored.steps()).hasSize(1);
        assertThat(restored.steps().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(restored.steps().getFirst().handoffContext()).isEqualTo("full handoff");
    }

    @Test
    void mutatingStepFailureFailsDirectorRun() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaWorkflowNodeHandler storyboard = mock(MangaWorkflowNodeHandler.class);
        MangaAgentExecutionSupport support = mock(MangaAgentExecutionSupport.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(registry, runService, objectMapper, support,
                new ExecutionPlanValidator());
        UUID requestId = UUID.randomUUID();
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(requestId);
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.95, List.of("write", "review"), true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW), RoutingDecision.CURRENT_VERSION);
        when(runService.findRun(1L, 7L, requestId)).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(storyboard);
        when(registry.handlerFor(MangaWorkflowRoute.REVIEW)).thenReturn(mock(MangaWorkflowNodeHandler.class));
        when(storyboard.run(any())).thenThrow(new BusinessException(502, "write failed"));

        assertThatThrownBy(() -> node.run(TestContexts.context(requestId, run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Director plan failed");
    }

    @Test
    void rejectsPlanWithDirectorStep() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(
                registry, runService, objectMapper, mock(MangaAgentExecutionSupport.class),
                new ExecutionPlanValidator());

        // Build a RoutingDecision with DIRECTOR in suggestedSteps
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("director"),
                false, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.DIRECTOR),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);

        // The plan validation should reject DIRECTOR in steps
        assertThatThrownBy(() -> node.run(TestContexts.context(run.getRequestId(), run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("recursive_director");
    }

    @Test
    void rejectsPlanWithTooManySteps() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(
                registry, runService, objectMapper, mock(MangaAgentExecutionSupport.class),
                new ExecutionPlanValidator());

        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("multi"),
                false, false, "test",
                List.of(MangaWorkflowRoute.CREATIVE, MangaWorkflowRoute.STORYBOARD,
                        MangaWorkflowRoute.REVIEW, MangaWorkflowRoute.CONVERSATION),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);

        assertThatThrownBy(() -> node.run(TestContexts.context(run.getRequestId(), run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("too_many_steps");
    }

    @Test
    void rejectsPlanWithMultipleMutatingSteps() {
        MangaWorkflowNodeRegistry registry = mock(MangaWorkflowNodeRegistry.class);
        when(registry.handlerFor(MangaWorkflowRoute.STORYBOARD)).thenReturn(mock());
        when(registry.handlerFor(MangaWorkflowRoute.CONVERSATION)).thenReturn(mock());
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(
                registry, runService, objectMapper, mock(MangaAgentExecutionSupport.class),
                new ExecutionPlanValidator());

        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR, 0.9, List.of("write"),
                true, false, "test",
                List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.STORYBOARD),
                RoutingDecision.CURRENT_VERSION
        );
        MangaAgentRun run = new MangaAgentRun();
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(MangaWorkflowRoute.DIRECTOR);
        when(runService.findRun(1L, 7L, run.getRequestId())).thenReturn(Optional.of(run));
        when(runService.routingDecision(run)).thenReturn(decision);

        assertThatThrownBy(() -> node.run(TestContexts.context(run.getRequestId(), run)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("duplicate_mutating_step");
    }

    private String toJson(RoutingDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (Exception error) {
            throw new AssertionError("Failed to serialize routing decision fixture", error);
        }
    }

}
