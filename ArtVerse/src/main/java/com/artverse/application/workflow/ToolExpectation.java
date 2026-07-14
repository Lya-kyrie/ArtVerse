package com.artverse.application.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ToolExpectation(
        String stepId,
        MangaWorkflowRoute route,
        RoutingDecision.ExpectedToolPolicy policy,
        Set<String> requiredTools,
        Set<String> forbiddenTools,
        Map<String, Integer> maxCalls,
        boolean requireValidDraftBeforeCommit
) {
    private static final Set<String> WRITE_TOOLS = Set.of(
            "generate_storyboard",
            "save_storyboard",
            "save_structured_storyboard",
            "commit_storyboard"
    );

    private static final Set<String> LEGACY_STORYBOARD_WRITES = Set.of(
            "generate_storyboard",
            "save_storyboard",
            "save_structured_storyboard"
    );

    public static ToolExpectation forRoute(MangaWorkflowExecutionContext context, MangaWorkflowRoute route) {
        MangaWorkflowRoute safeRoute = route == null ? MangaWorkflowRoute.CONVERSATION : route;
        String stepId = context.stepId() == null || context.stepId().isBlank()
                ? defaultStepId(safeRoute)
                : context.stepId();
        return switch (safeRoute) {
            case STORYBOARD -> new ToolExpectation(
                    stepId,
                    safeRoute,
                    RoutingDecision.ExpectedToolPolicy.WRITE_WITH_HITL,
                    Set.of("draft_structured_storyboard", "commit_storyboard"),
                    LEGACY_STORYBOARD_WRITES,
                    Map.of("commit_storyboard", 1),
                    true
            );
            case DIRECTOR -> new ToolExpectation(
                    stepId,
                    safeRoute,
                    RoutingDecision.ExpectedToolPolicy.DIRECTOR_ORCHESTRATION,
                    Set.of(),
                    WRITE_TOOLS,
                    Map.of(),
                    false
            );
            default -> new ToolExpectation(
                    stepId,
                    safeRoute,
                    RoutingDecision.ExpectedToolPolicy.READ_ONLY_CONTEXT,
                    Set.of(),
                    WRITE_TOOLS,
                    Map.of(),
                    false
            );
        };
    }

    public List<String> requiredToolsInOrder() {
        return route == MangaWorkflowRoute.STORYBOARD
                ? List.of("draft_structured_storyboard", "commit_storyboard")
                : List.copyOf(requiredTools);
    }

    private static String defaultStepId(MangaWorkflowRoute route) {
        return switch (route == null ? MangaWorkflowRoute.CONVERSATION : route) {
            case CONVERSATION -> "manga-conversation";
            case CREATIVE -> "manga-creative";
            case STORYBOARD -> "manga-storyboard";
            case REVIEW -> "manga-review";
            case DIRECTOR -> "manga-director";
        };
    }
}
