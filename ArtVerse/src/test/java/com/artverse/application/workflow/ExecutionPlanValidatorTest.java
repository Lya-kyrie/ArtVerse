package com.artverse.application.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanValidatorTest {

    private final ExecutionPlanValidator validator = new ExecutionPlanValidator();

    @Test
    void acceptsSingleAndCompoundCapabilityCoveringPlans() {
        assertThat(validator.validate(List.of(MangaWorkflowRoute.REVIEW),
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW)).valid()).isTrue();
        assertThat(validator.validate(List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.REVIEW),
                Set.of(MangaWorkflowCapability.STORYBOARD_WRITE,
                        MangaWorkflowCapability.STORYBOARD_REVIEW)).valid()).isTrue();
    }

    @Test
    void rejectsMalformedOrRecursivePlans() {
        assertReason(List.of(), Set.of(), "empty_steps");
        assertReason(List.of(MangaWorkflowRoute.DIRECTOR), Set.of(), "recursive_director");
        assertReason(List.of(MangaWorkflowRoute.CONVERSATION, MangaWorkflowRoute.CREATIVE,
                MangaWorkflowRoute.REVIEW, MangaWorkflowRoute.STORYBOARD), Set.of(), "too_many_steps");
        ArrayList<MangaWorkflowRoute> withNull = new ArrayList<>();
        withNull.add(null);
        assertReason(withNull, Set.of(), "null_step");
    }

    @Test
    void rejectsUnsafeMutationAndCapabilityMismatch() {
        assertReason(List.of(MangaWorkflowRoute.STORYBOARD, MangaWorkflowRoute.STORYBOARD),
                Set.of(MangaWorkflowCapability.STORYBOARD_WRITE), "duplicate_mutating_step");
        assertReason(List.of(MangaWorkflowRoute.CREATIVE),
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW), "capability_route_mismatch");
        LinkedHashSet<MangaWorkflowCapability> withNull = new LinkedHashSet<>();
        withNull.add(null);
        assertReason(List.of(MangaWorkflowRoute.CONVERSATION), withNull, "null_capability");
    }

    private void assertReason(List<MangaWorkflowRoute> routes,
                              Set<MangaWorkflowCapability> capabilities,
                              String reason) {
        assertThat(validator.validate(routes, capabilities).reasonCode()).isEqualTo(reason);
    }
}
