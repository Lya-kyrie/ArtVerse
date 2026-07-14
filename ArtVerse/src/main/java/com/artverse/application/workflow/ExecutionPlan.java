package com.artverse.application.workflow;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A validated multi-step execution plan for the Director agent.
 * Created from {@link RoutingDecision#suggestedSteps()} and validated
 * before any step executes.
 */
public record ExecutionPlan(
        String planId,
        List<ExecutionStep> steps,
        String routerVersion,
        OffsetDateTime createdAt
) {
    public static final int MAX_STEPS = 3;

    public ExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public int stepCount() {
        return steps.size();
    }
}
