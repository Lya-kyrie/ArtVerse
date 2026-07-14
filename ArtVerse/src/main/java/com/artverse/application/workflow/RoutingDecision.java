package com.artverse.application.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RoutingDecision(
        MangaWorkflowRoute route,
        double confidence,
        List<String> intents,
        boolean mutating,
        boolean needsClarification,
        String reasonCode,
        List<MangaWorkflowRoute> suggestedSteps,
        String routerVersion,
        Set<MangaWorkflowCapability> requiredCapabilities,
        ExpectedToolPolicy expectedToolPolicy,
        Set<String> requiredContextFields,
        RouteOutputContract outputContract,
        RouteFallbackReason fallbackReason
) {
    public static final String CURRENT_VERSION = "v3-route-contract";

    public RoutingDecision {
        intents = intents == null ? List.of() : List.copyOf(intents);
        suggestedSteps = suggestedSteps == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(suggestedSteps));
        reasonCode = reasonCode == null ? "unspecified" : reasonCode;
        routerVersion = routerVersion == null ? CURRENT_VERSION : routerVersion;
        requiredCapabilities = requiredCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredCapabilities));
        List<MangaWorkflowRoute> contractRoutes = suggestedSteps.isEmpty() && route != null
                ? List.of(route)
                : suggestedSteps;
        expectedToolPolicy = expectedToolPolicy == null
                ? ExpectedToolPolicy.forRoutes(contractRoutes)
                : expectedToolPolicy;
        requiredContextFields = requiredContextFields == null || requiredContextFields.isEmpty()
                ? contextFieldsFor(contractRoutes)
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredContextFields));
        outputContract = outputContract == null
                ? RouteOutputContract.forRoute(routeForContract(route, contractRoutes))
                : outputContract;
    }

    public RoutingDecision(MangaWorkflowRoute route, double confidence, List<String> intents,
                           boolean mutating, boolean needsClarification, String reasonCode,
                           List<MangaWorkflowRoute> suggestedSteps, String routerVersion) {
        this(route, confidence, intents, mutating, needsClarification, reasonCode, suggestedSteps,
                routerVersion, Set.of());
    }

    public RoutingDecision(MangaWorkflowRoute route, double confidence, List<String> intents,
                           boolean mutating, boolean needsClarification, String reasonCode,
                           List<MangaWorkflowRoute> suggestedSteps, String routerVersion,
                           Set<MangaWorkflowCapability> requiredCapabilities) {
        this(route, confidence, intents, mutating, needsClarification, reasonCode, suggestedSteps,
                routerVersion, requiredCapabilities, null, null, null, null);
    }

    public static RoutingDecision fixed(MangaWorkflowRoute route, String reasonCode) {
        return new RoutingDecision(route, 1.0, List.of(), route.isMutating(),
                false, reasonCode, List.of(route), CURRENT_VERSION, route.capabilities());
    }

    public RoutingDecision withFallback(MangaWorkflowRoute route, double confidence, String reasonCode,
                                        RouteFallbackReason fallbackReason) {
        return new RoutingDecision(route, confidence, intents, false, false, reasonCode,
                List.of(route), CURRENT_VERSION, requiredCapabilities, ExpectedToolPolicy.forRoute(route),
                contextFieldsFor(List.of(route)), RouteOutputContract.forRoute(route), fallbackReason);
    }

    static Set<String> contextFieldsFor(List<MangaWorkflowRoute> routes) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        fields.add("conversation_summary");
        List<MangaWorkflowRoute> safeRoutes = routes == null ? List.of() : routes;
        if (safeRoutes.stream().anyMatch(route -> route == MangaWorkflowRoute.CREATIVE
                || route == MangaWorkflowRoute.STORYBOARD
                || route == MangaWorkflowRoute.DIRECTOR)) {
            fields.add("chapter_source_excerpt");
        }
        if (safeRoutes.stream().anyMatch(route -> route == MangaWorkflowRoute.CREATIVE
                || route == MangaWorkflowRoute.STORYBOARD
                || route == MangaWorkflowRoute.REVIEW
                || route == MangaWorkflowRoute.DIRECTOR)) {
            fields.add("character_summary");
        }
        if (safeRoutes.stream().anyMatch(route -> route == MangaWorkflowRoute.REVIEW
                || route == MangaWorkflowRoute.DIRECTOR)) {
            fields.add("storyboard_excerpt");
        }
        return Collections.unmodifiableSet(fields);
    }

    private static MangaWorkflowRoute routeForContract(MangaWorkflowRoute route, List<MangaWorkflowRoute> routes) {
        if (routes != null && routes.size() > 1) {
            return MangaWorkflowRoute.DIRECTOR;
        }
        if (routes != null && routes.size() == 1 && routes.getFirst() != null) {
            return routes.getFirst();
        }
        return route == null ? MangaWorkflowRoute.CONVERSATION : route;
    }

    public enum ExpectedToolPolicy {
        READ_ONLY_CONTEXT,
        WRITE_WITH_HITL,
        DIRECTOR_ORCHESTRATION;

        public static ExpectedToolPolicy forRoute(MangaWorkflowRoute route) {
            return route != null && route.isMutating() ? WRITE_WITH_HITL : READ_ONLY_CONTEXT;
        }

        public static ExpectedToolPolicy forRoutes(List<MangaWorkflowRoute> routes) {
            if (routes != null && routes.size() > 1) {
                return DIRECTOR_ORCHESTRATION;
            }
            MangaWorkflowRoute route = routes == null || routes.isEmpty() ? MangaWorkflowRoute.CONVERSATION : routes.getFirst();
            return forRoute(route);
        }
    }

    public record RouteOutputContract(String schemaName, boolean mutating, boolean finalAssistantReply) {
        public static RouteOutputContract forRoute(MangaWorkflowRoute route) {
            MangaWorkflowRoute safeRoute = route == null ? MangaWorkflowRoute.CONVERSATION : route;
            return switch (safeRoute) {
                case CONVERSATION -> new RouteOutputContract("conversation.reply", false, true);
                case CREATIVE -> new RouteOutputContract("creative.guidance", false, true);
                case STORYBOARD -> new RouteOutputContract("storyboard.mutation_result", true, true);
                case REVIEW -> new RouteOutputContract("review.report", false, true);
                case DIRECTOR -> new RouteOutputContract("director.final_reply", false, true);
            };
        }
    }

    public record RouteFallbackReason(String category, String code, Map<String, String> details) {
        public RouteFallbackReason {
            category = category == null ? "unknown" : category;
            code = code == null ? "unspecified" : code;
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static RouteFallbackReason of(String category, String code) {
            return new RouteFallbackReason(category, code, Map.of());
        }
    }
}
