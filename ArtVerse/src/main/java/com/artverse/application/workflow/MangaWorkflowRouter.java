package com.artverse.application.workflow;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.workflow.prefilter.RouterPreFilter;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
public class MangaWorkflowRouter {

    private final AgentScopeHarnessAgentGateway gateway;
    private final ArtVerseProperties properties;
    private final List<RouterPreFilter> preFilters;
    private final MangaRoutingMetrics metrics;
    private final ExecutionPlanValidator planValidator;
    private final RouteContractValidator contractValidator;

    public MangaWorkflowRouter(AgentScopeHarnessAgentGateway gateway, ArtVerseProperties properties,
                               List<RouterPreFilter> preFilters, MangaRoutingMetrics metrics,
                               ExecutionPlanValidator planValidator,
                               RouteContractValidator contractValidator) {
        this.gateway = gateway;
        this.properties = properties;
        this.preFilters = preFilters.stream()
                .sorted(Comparator.comparingInt(RouterPreFilter::getOrder))
                .toList();
        this.metrics = metrics;
        this.planValidator = planValidator;
        this.contractValidator = contractValidator;
    }

    public RoutingDecision route(MangaAgentConversation conversation, String message, UUID requestId,
                                 AgentModelSpec modelSpec, String llmApiKey) {
        if (!properties.getAgent().isAutoRoutingEnabled()) {
            RoutingDecision decision = RoutingDecision.fixed(
                            MangaWorkflowRoute.CONVERSATION, "automatic_routing_disabled_safe_fallback")
                    .withFallback(MangaWorkflowRoute.CONVERSATION, 1.0,
                            "automatic_routing_disabled_safe_fallback",
                            fallbackReason("automatic_routing_disabled_safe_fallback"));
            metrics.recordDecision(decision, "disabled");
            return decision;
        }

        // Deterministic validation only; business support is decided by capabilities below.
        Optional<RoutingDecision> preFiltered = preFilter(message);
        if (preFiltered.isPresent()) {
            metrics.recordDecision(preFiltered.get(), "prefilter");
            return preFiltered.get();
        }

        RoutingDecision raw;
        long startedAt = System.nanoTime();
        try {
            raw = gateway.generateStructured(routerRequest(conversation, message, requestId, modelSpec, llmApiKey),
                    RoutingDecision.class).block();
            metrics.recordLatency(System.nanoTime() - startedAt, "success");
        } catch (Exception error) {
            metrics.recordLatency(System.nanoTime() - startedAt, "failure");
            log.warn("Manga router failed for request {}; using read-only fallback", requestId, error);
            RoutingDecision fallback = RoutingDecision.fixed(
                            MangaWorkflowRoute.CONVERSATION, "router_unavailable_safe_fallback")
                    .withFallback(MangaWorkflowRoute.CONVERSATION, 1.0,
                            "router_unavailable_safe_fallback",
                            fallbackReason("router_unavailable_safe_fallback"));
            metrics.recordFallback("router_unavailable");
            metrics.recordDecision(fallback, "fallback");
            return fallback;
        }
        try {
            boolean shadowMode = properties.getAgent().isRoutingShadowMode();
            RoutingDecision decision = validate(raw, shadowMode);
            metrics.recordDecision(decision, shadowMode ? "shadow" : "semantic");
            return decision;
        } catch (AgentUserInputRequiredException clarification) {
            metrics.recordClarification(raw != null && (raw.mutating()
                    || raw.requiredCapabilities().stream().anyMatch(MangaWorkflowCapability::isMutating)));
            throw clarification;
        }
    }

    /**
     * Pre-filter structurally invalid messages before the semantic router.
     * Returns an empty result if the message should be processed by the LLM router.
     */
    private Optional<RoutingDecision> preFilter(String message) {
        for (RouterPreFilter preFilter : preFilters) {
            Optional<RoutingDecision> decision = preFilter.filter(message);
            if (decision.isPresent()) {
                return decision;
            }
        }
        return Optional.empty();
    }

    private RoutingDecision validate(RoutingDecision raw, boolean shadowMode) {
        if (raw == null || raw.route() == null) {
            return RoutingDecision.fixed(MangaWorkflowRoute.CONVERSATION, "invalid_router_output")
                    .withFallback(MangaWorkflowRoute.CONVERSATION, 1.0,
                            "invalid_router_output", fallbackReason("invalid_router_output"));
        }
        double rawConfidence = raw.confidence();
        if (rawConfidence < 0.0 || rawConfidence > 1.0) {
            log.warn("Router returned out-of-range confidence={} (reasonCode={}); clamping to [0,1]",
                    rawConfidence, raw.reasonCode());
        }
        double confidence = Math.max(0.0, Math.min(1.0, rawConfidence));
        List<MangaWorkflowRoute> suggestedSteps = raw.suggestedSteps();
        Set<MangaWorkflowCapability> requiredCapabilities = raw.requiredCapabilities();
        if (requiredCapabilities.stream().anyMatch(capability -> capability == null)) {
            return fallbackDecision(raw, confidence, "invalid_plan:null_capability");
        }
        Set<MangaWorkflowCapability> unavailable = MangaWorkflowCapability.unavailable(requiredCapabilities);
        if (!unavailable.isEmpty()) {
            String unsupported = unavailable.stream().map(Enum::name).sorted()
                    .reduce((left, right) -> left + "," + right).orElse("");
            log.info("Router request requires unavailable manga capabilities: {}", unsupported);
            return fallbackDecision(raw, confidence, "unsupported_capability:" + unsupported);
        }
        ExecutionPlanValidator.ValidationResult planValidation =
                planValidator.validate(suggestedSteps, requiredCapabilities);
        if (!planValidation.valid()) {
            Optional<List<MangaWorkflowRoute>> repairedSteps =
                    repairUnsafeReadOnlyReviewPlan(suggestedSteps, requiredCapabilities, planValidation.reasonCode());
            if (repairedSteps.isPresent()) {
                log.info("Router corrected read-only review plan {} for required capabilities {}",
                        suggestedSteps, requiredCapabilities);
                suggestedSteps = repairedSteps.get();
                planValidation = planValidator.validate(suggestedSteps, requiredCapabilities);
            }
        }
        if (!planValidation.valid()) {
            return fallbackDecision(raw, confidence, "invalid_plan:" + planValidation.reasonCode());
        }

        MangaWorkflowRoute route = suggestedSteps.size() > 1
                ? MangaWorkflowRoute.DIRECTOR
                : suggestedSteps.getFirst();
        RoutingDecision candidate = normalizedDecision(raw, confidence, route, suggestedSteps, requiredCapabilities);
        RouteContractValidator.ValidationResult contractValidation = contractValidator.validate(candidate);
        if (!contractValidation.valid()) {
            return fallbackDecision(raw, confidence, contractValidation.reasonCode());
        }
        boolean writeRoute = suggestedSteps.stream().anyMatch(MangaWorkflowRoute::isMutating);

        // Shadow mode: never throw clarification, never fallback — always return safe CONVERSATION
        if (shadowMode) {
            return new RoutingDecision(
                    MangaWorkflowRoute.CONVERSATION,
                    confidence,
                    raw.intents(),
                    false,
                    false,
                    "shadow:" + route.name(),
                    List.of(MangaWorkflowRoute.CONVERSATION),
                    RoutingDecision.CURRENT_VERSION,
                    requiredCapabilities,
                    RoutingDecision.ExpectedToolPolicy.forRoute(MangaWorkflowRoute.CONVERSATION),
                    RoutingDecision.contextFieldsFor(List.of(MangaWorkflowRoute.CONVERSATION)),
                    RoutingDecision.RouteOutputContract.forRoute(MangaWorkflowRoute.CONVERSATION),
                    null);
        }

        if (raw.needsClarification()
                || (writeRoute && confidence < properties.getAgent().getRoutingDirectThreshold())) {
            throw routingClarification(raw.reasonCode());
        }
        if (!writeRoute && confidence < properties.getAgent().getRoutingReadOnlyThreshold()) {
            return fallbackDecision(raw, confidence, "low_confidence_read_only_fallback");
        }
        return candidate;
    }

    private Optional<List<MangaWorkflowRoute>> repairUnsafeReadOnlyReviewPlan(
            List<MangaWorkflowRoute> suggestedSteps,
            Set<MangaWorkflowCapability> requiredCapabilities,
            String validationReason) {
        if (!"capability_route_mismatch".equals(validationReason)
                || suggestedSteps == null
                || suggestedSteps.size() != 1
                || suggestedSteps.getFirst() != MangaWorkflowRoute.STORYBOARD
                || requiredCapabilities == null
                || requiredCapabilities.stream().anyMatch(MangaWorkflowCapability::isMutating)) {
            return Optional.empty();
        }
        if (MangaWorkflowRoute.REVIEW.capabilities().containsAll(requiredCapabilities)
                && requiredCapabilities.contains(MangaWorkflowCapability.STORYBOARD_REVIEW)) {
            return Optional.of(List.of(MangaWorkflowRoute.REVIEW));
        }
        return Optional.empty();
    }

    private RoutingDecision fallbackDecision(RoutingDecision raw, double confidence, String reason) {
        log.warn("Router returned invalid plan {} for required capabilities {}: {}",
                raw.suggestedSteps(), raw.requiredCapabilities(), reason);
        metrics.recordFallback(reason);
        return raw.withFallback(MangaWorkflowRoute.CONVERSATION, confidence, reason, fallbackReason(reason));
    }

    private RoutingDecision normalizedDecision(RoutingDecision raw, double confidence, MangaWorkflowRoute route,
                                               List<MangaWorkflowRoute> suggestedSteps,
                                               Set<MangaWorkflowCapability> requiredCapabilities) {
        boolean writeRoute = suggestedSteps.stream().anyMatch(MangaWorkflowRoute::isMutating);
        // The model classifies intent and capabilities only. Execution policy,
        // context requirements, and the result schema are compiled by the
        // application, so a forged model contract can never widen authority.
        return new RoutingDecision(route, confidence, raw.intents(), writeRoute, false, raw.reasonCode(),
                suggestedSteps, RoutingDecision.CURRENT_VERSION, requiredCapabilities,
                RoutingDecision.ExpectedToolPolicy.forRoutes(suggestedSteps),
                RoutingDecision.contextFieldsFor(suggestedSteps),
                RoutingDecision.RouteOutputContract.forRoute(route),
                null);
    }

    private RoutingDecision.RouteFallbackReason fallbackReason(String reason) {
        String category = reason;
        String code = reason;
        int separator = reason.indexOf(':');
        if (separator > 0) {
            category = reason.substring(0, separator);
            code = reason.substring(separator + 1);
        }
        return RoutingDecision.RouteFallbackReason.of(category, code);
    }

    private AgentUserInputRequiredException routingClarification(String reason) {
        return new AgentUserInputRequiredException(new AgentUserInputRequest(
                "这个请求可能会修改当前章节的分镜，请确认你希望执行的操作。",
                List.of(
                        new AgentUserInputRequest.Option("edit", "修改分镜", "允许分镜智能体生成或保存修改", true),
                        new AgentUserInputRequest.Option("advice", "只给建议", "只分析并提供建议，不保存任何内容", false)
                ),
                true,
                reason == null ? "routing_ambiguous" : reason,
                "ROUTING"
        ));
    }

    private AgentRunRequest routerRequest(MangaAgentConversation conversation, String message, UUID requestId,
                                          AgentModelSpec modelSpec, String llmApiKey) {
        String input = "Available capability catalog: " + MangaWorkflowCapability.routingCatalog()
                + "\nReturn every capability required by the request in requiredCapabilities. "
                + "The application owns execution policy, context requirements, result contracts, and fallbacks; "
                + "suggestedSteps is only a routing hint. "
                + "Use CONVERSATION when any required capability is UNAVAILABLE."
                + "\nClassify this user request for the current ArtVerse chapter:\n" + message;
        return new AgentRunRequest(
                String.valueOf(conversation.getUser().getId()),
                conversation.getStory().getId(),
                conversation.getChapter().getId(),
                AgentTaskType.MANGA_ROUTER,
                List.of(new AgentMessage("user", input)),
                Map.of(),
                modelSpec,
                llmApiKey,
                requestId,
                conversation.getConversationUuid()
        );
    }
}
