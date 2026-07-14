package com.artverse.application.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class RouteContractValidator {

    private static final Set<String> ALLOWED_CONTEXT_FIELDS = Set.of(
            "chapter_source_excerpt", "storyboard_excerpt", "character_summary", "conversation_summary");

    private final ExecutionPlanValidator planValidator;

    public RouteContractValidator(ExecutionPlanValidator planValidator) {
        this.planValidator = planValidator;
    }

    public ValidationResult validate(RoutingDecision decision) {
        if (decision == null || decision.route() == null) {
            return ValidationResult.invalid("invalid_router_output");
        }
        if (decision.requiredCapabilities().stream().anyMatch(capability -> capability == null)) {
            return ValidationResult.invalid("invalid_plan:null_capability");
        }
        Set<MangaWorkflowCapability> unavailable = MangaWorkflowCapability.unavailable(decision.requiredCapabilities());
        if (!unavailable.isEmpty()) {
            return ValidationResult.invalid("unsupported_capability:" + sortedNames(unavailable));
        }

        List<MangaWorkflowRoute> routes = routesOf(decision);
        ExecutionPlanValidator.ValidationResult planValidation =
                planValidator.validate(routes, decision.requiredCapabilities());
        if (!planValidation.valid()) {
            return ValidationResult.invalid("invalid_plan:" + planValidation.reasonCode());
        }

        MangaWorkflowRoute effectiveRoute = effectiveRoute(routes);
        RoutingDecision.ExpectedToolPolicy expectedPolicy = RoutingDecision.ExpectedToolPolicy.forRoutes(routes);
        if (decision.expectedToolPolicy() != expectedPolicy) {
            return ValidationResult.invalid("invalid_contract:tool_policy_mismatch");
        }

        Set<String> expectedContext = RoutingDecision.contextFieldsFor(routes);
        if (decision.requiredContextFields() == null || decision.requiredContextFields().isEmpty()) {
            return ValidationResult.invalid("invalid_contract:missing_context_fields");
        }
        if (!ALLOWED_CONTEXT_FIELDS.containsAll(decision.requiredContextFields())) {
            return ValidationResult.invalid("invalid_contract:unknown_context_field");
        }
        if (!decision.requiredContextFields().containsAll(expectedContext)) {
            return ValidationResult.invalid("invalid_contract:context_fields_incomplete");
        }

        RoutingDecision.RouteOutputContract expectedOutput =
                RoutingDecision.RouteOutputContract.forRoute(effectiveRoute);
        if (!expectedOutput.equals(decision.outputContract())) {
            return ValidationResult.invalid("invalid_contract:output_contract_mismatch");
        }
        return ValidationResult.success(effectiveRoute, routes);
    }

    private List<MangaWorkflowRoute> routesOf(RoutingDecision decision) {
        if (decision.suggestedSteps() == null || decision.suggestedSteps().isEmpty()) {
            return List.of(decision.route());
        }
        return List.copyOf(decision.suggestedSteps());
    }

    private MangaWorkflowRoute effectiveRoute(List<MangaWorkflowRoute> routes) {
        return routes.size() > 1 ? MangaWorkflowRoute.DIRECTOR : routes.getFirst();
    }

    private String sortedNames(Set<MangaWorkflowCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    public record ValidationResult(boolean valid, String reasonCode,
                                   MangaWorkflowRoute effectiveRoute,
                                   List<MangaWorkflowRoute> routes) {
        public ValidationResult {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }

        public static ValidationResult success(MangaWorkflowRoute route, List<MangaWorkflowRoute> routes) {
            return new ValidationResult(true, "valid", route, routes);
        }

        public static ValidationResult invalid(String reasonCode) {
            return new ValidationResult(false, reasonCode, MangaWorkflowRoute.CONVERSATION, List.of());
        }
    }
}
