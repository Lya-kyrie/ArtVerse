package com.artverse.application.workflow;

import com.artverse.common.BusinessException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Compiles an immutable, application-controlled plan from a router decision.
 * The compiler is the boundary that prevents an LLM from adding routes after
 * routing has completed.
 */
@Component
public class ExecutionPlanCompiler {

    private final ExecutionPlanValidator validator;

    public ExecutionPlanCompiler(ExecutionPlanValidator validator) {
        this.validator = validator;
    }

    public ExecutionPlan compile(RoutingDecision decision, Predicate<MangaWorkflowRoute> routeAvailable) {
        if (decision == null) {
            throw new BusinessException(400, "Routing decision is required");
        }
        List<MangaWorkflowRoute> routes = routesOf(decision);
        validator.requireValid(routes, decision.requiredCapabilities());

        Predicate<MangaWorkflowRoute> availability = routeAvailable == null ? ignored -> true : routeAvailable;
        List<ExecutionStep> steps = new ArrayList<>(routes.size());
        for (int index = 0; index < routes.size(); index++) {
            MangaWorkflowRoute route = routes.get(index);
            if (!availability.test(route)) {
                throw new BusinessException(400, "Execution plan contains unavailable route: " + route);
            }
            steps.add(new ExecutionStep(index, route, route.name(), route.isMutating()));
        }

        String routerVersion = decision.routerVersion() == null
                ? RoutingDecision.CURRENT_VERSION
                : decision.routerVersion();
        return new ExecutionPlan(UUID.randomUUID().toString(), steps, routerVersion, OffsetDateTime.now());
    }

    public List<MangaWorkflowRoute> routesOf(RoutingDecision decision) {
        if (decision.suggestedSteps() == null || decision.suggestedSteps().isEmpty()) {
            return List.of(decision.route());
        }
        return List.copyOf(decision.suggestedSteps());
    }
}
