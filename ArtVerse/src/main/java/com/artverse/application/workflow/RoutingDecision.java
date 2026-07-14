package com.artverse.application.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
        Set<MangaWorkflowCapability> requiredCapabilities
) {
    public static final String CURRENT_VERSION = "v2-capabilities";

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
    }

    public RoutingDecision(MangaWorkflowRoute route, double confidence, List<String> intents,
                           boolean mutating, boolean needsClarification, String reasonCode,
                           List<MangaWorkflowRoute> suggestedSteps, String routerVersion) {
        this(route, confidence, intents, mutating, needsClarification, reasonCode, suggestedSteps,
                routerVersion, Set.of());
    }

    public static RoutingDecision fixed(MangaWorkflowRoute route, String reasonCode) {
        return new RoutingDecision(route, 1.0, List.of(), route.isMutating(),
                false, reasonCode, List.of(route), CURRENT_VERSION, route.capabilities());
    }
}
