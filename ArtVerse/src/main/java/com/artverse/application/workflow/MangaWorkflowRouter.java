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
import java.util.stream.Collectors;

@Slf4j
@Component
public class MangaWorkflowRouter {

    private final AgentScopeHarnessAgentGateway gateway;
    private final ArtVerseProperties properties;
    private final List<RouterPreFilter> preFilters;
    private final MangaRoutingMetrics metrics;
    private final ExecutionPlanValidator planValidator;

    public MangaWorkflowRouter(AgentScopeHarnessAgentGateway gateway, ArtVerseProperties properties,
                               List<RouterPreFilter> preFilters, MangaRoutingMetrics metrics,
                               ExecutionPlanValidator planValidator) {
        this.gateway = gateway;
        this.properties = properties;
        this.preFilters = preFilters.stream()
                .sorted(Comparator.comparingInt(RouterPreFilter::getOrder))
                .toList();
        this.metrics = metrics;
        this.planValidator = planValidator;
    }

    public RoutingDecision route(MangaAgentConversation conversation, String message, UUID requestId,
                                 AgentModelSpec modelSpec, String llmApiKey) {
        if (!properties.getAgent().isAutoRoutingEnabled()) {
            RoutingDecision decision = RoutingDecision.fixed(
                    MangaWorkflowRoute.CONVERSATION, "automatic_routing_disabled_safe_fallback");
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
                    MangaWorkflowRoute.CONVERSATION, "router_unavailable_safe_fallback");
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
            return RoutingDecision.fixed(MangaWorkflowRoute.CONVERSATION, "invalid_router_output");
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
            return invalidPlanDecision(raw, confidence, requiredCapabilities, "null_capability");
        }
        Set<MangaWorkflowCapability> unavailable = MangaWorkflowCapability.unavailable(requiredCapabilities);
        if (!unavailable.isEmpty()) {
            return unsupportedCapabilityDecision(raw, confidence, requiredCapabilities, unavailable);
        }
        ExecutionPlanValidator.ValidationResult planValidation =
                planValidator.validate(suggestedSteps, requiredCapabilities);
        if (!planValidation.valid()) {
            return invalidPlanDecision(raw, confidence, requiredCapabilities, planValidation.reasonCode());
        }

        MangaWorkflowRoute route = suggestedSteps.size() > 1
                ? MangaWorkflowRoute.DIRECTOR
                : suggestedSteps.getFirst();
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
                    requiredCapabilities);
        }

        if (raw.needsClarification()
                || (writeRoute && confidence < properties.getAgent().getRoutingDirectThreshold())) {
            throw routingClarification(raw.reasonCode());
        }
        if (!writeRoute && confidence < properties.getAgent().getRoutingReadOnlyThreshold()) {
            return new RoutingDecision(MangaWorkflowRoute.CONVERSATION, confidence, raw.intents(), false,
                    false, "low_confidence_read_only_fallback", List.of(MangaWorkflowRoute.CONVERSATION),
                    RoutingDecision.CURRENT_VERSION, requiredCapabilities);
        }
        return new RoutingDecision(route, confidence, raw.intents(), writeRoute, false, raw.reasonCode(),
                suggestedSteps, RoutingDecision.CURRENT_VERSION, requiredCapabilities);
    }

    private RoutingDecision invalidPlanDecision(RoutingDecision raw, double confidence,
                                                Set<MangaWorkflowCapability> requiredCapabilities,
                                                String validationReason) {
        String reason = "invalid_plan:" + validationReason;
        log.warn("Router returned invalid plan {} for required capabilities {}: {}",
                raw.suggestedSteps(), requiredCapabilities, validationReason);
        metrics.recordFallback(reason);
        return new RoutingDecision(MangaWorkflowRoute.CONVERSATION, confidence, raw.intents(), false,
                false, reason, List.of(MangaWorkflowRoute.CONVERSATION),
                RoutingDecision.CURRENT_VERSION, requiredCapabilities);
    }

    private RoutingDecision unsupportedCapabilityDecision(RoutingDecision raw, double confidence,
                                                            Set<MangaWorkflowCapability> requiredCapabilities,
                                                            Set<MangaWorkflowCapability> unavailable) {
        String unsupported = unavailable.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        log.info("Router request requires unavailable manga capabilities: {}", unsupported);
        return new RoutingDecision(MangaWorkflowRoute.CONVERSATION, confidence, raw.intents(), false,
                false, "unsupported_capability:" + unsupported, List.of(MangaWorkflowRoute.CONVERSATION),
                RoutingDecision.CURRENT_VERSION, requiredCapabilities);
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
