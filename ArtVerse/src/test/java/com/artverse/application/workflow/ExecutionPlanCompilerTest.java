package com.artverse.application.workflow;

import com.artverse.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionPlanCompilerTest {

    private final ExecutionPlanCompiler compiler = new ExecutionPlanCompiler(new ExecutionPlanValidator());

    @Test
    void compilesOnlyRouterApprovedSteps() {
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.DIRECTOR,
                0.94,
                List.of("multi_step"),
                false,
                false,
                "test",
                List.of(MangaWorkflowRoute.CREATIVE, MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION
        );

        ExecutionPlan plan = compiler.compile(decision, route -> true);

        assertThat(plan.steps()).extracting(ExecutionStep::route)
                .containsExactly(MangaWorkflowRoute.CREATIVE, MangaWorkflowRoute.REVIEW);
        assertThat(plan.steps()).extracting(ExecutionStep::sequence).containsExactly(0, 1);
        assertThat(plan.steps()).allMatch(step -> "PENDING".equals(step.status()));
    }

    @Test
    void rejectsUnavailableRouteBeforeExecution() {
        RoutingDecision decision = new RoutingDecision(
                MangaWorkflowRoute.REVIEW,
                0.90,
                List.of(),
                false,
                false,
                "test",
                List.of(MangaWorkflowRoute.REVIEW),
                RoutingDecision.CURRENT_VERSION
        );

        assertThatThrownBy(() -> compiler.compile(decision, route -> false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unavailable route");
    }
}
