package com.artverse.application.workflow;

import com.artverse.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ExecutionPlanValidator {

    public ValidationResult validate(List<MangaWorkflowRoute> routes,
                                     Set<MangaWorkflowCapability> requiredCapabilities) {
        if (routes == null || routes.isEmpty()) {
            return ValidationResult.invalid("empty_steps");
        }
        if (routes.size() > ExecutionPlan.MAX_STEPS) {
            return ValidationResult.invalid("too_many_steps");
        }
        if (routes.stream().anyMatch(route -> route == null)) {
            return ValidationResult.invalid("null_step");
        }
        if (routes.contains(MangaWorkflowRoute.DIRECTOR)) {
            return ValidationResult.invalid("recursive_director");
        }

        Set<MangaWorkflowRoute> seenMutatingRoutes = new HashSet<>();
        for (MangaWorkflowRoute route : routes) {
            if (route.isMutating() && !seenMutatingRoutes.add(route)) {
                return ValidationResult.invalid("duplicate_mutating_step");
            }
        }
        long mutatingCount = routes.stream().filter(MangaWorkflowRoute::isMutating).count();
        if (mutatingCount > 1) {
            return ValidationResult.invalid("multiple_mutating_steps");
        }

        Set<MangaWorkflowCapability> required = requiredCapabilities == null
                ? Set.of()
                : requiredCapabilities;
        if (required.stream().anyMatch(capability -> capability == null)) {
            return ValidationResult.invalid("null_capability");
        }
        EnumSet<MangaWorkflowCapability> provided = EnumSet.noneOf(MangaWorkflowCapability.class);
        routes.forEach(route -> provided.addAll(route.capabilities()));
        if (!provided.containsAll(required)) {
            return ValidationResult.invalid("capability_route_mismatch");
        }
        return ValidationResult.success();
    }

    public void requireValid(List<MangaWorkflowRoute> routes,
                             Set<MangaWorkflowCapability> requiredCapabilities) {
        ValidationResult result = validate(routes, requiredCapabilities);
        if (!result.valid()) {
            throw new BusinessException(400, "Invalid Director execution plan: " + result.reasonCode());
        }
    }

    public record ValidationResult(boolean valid, String reasonCode) {
        public static ValidationResult success() {
            return new ValidationResult(true, "valid");
        }

        public static ValidationResult invalid(String reasonCode) {
            return new ValidationResult(false, reasonCode);
        }
    }
}
