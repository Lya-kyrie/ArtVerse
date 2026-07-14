package com.artverse.application.workflow;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MangaRoutingMetrics {

    private final MeterRegistry meterRegistry;

    public void recordDecision(RoutingDecision decision, String source) {
        meterRegistry.counter(
                "artverse.agent.routing.decisions",
                "route", decision.route().name(),
                "source", source,
                "mutating", Boolean.toString(decision.mutating()))
                .increment();
        meterRegistry.summary("artverse.agent.routing.confidence", "route", decision.route().name())
                .record(decision.confidence());
    }

    public void recordFallback(String source) {
        meterRegistry.counter("artverse.agent.routing.fallbacks", "source", source).increment();
    }

    public void recordClarification(boolean mutating) {
        meterRegistry.counter(
                "artverse.agent.routing.clarifications",
                "mutating", Boolean.toString(mutating)).increment();
    }

    public void recordLatency(long elapsedNanos, String outcome) {
        Timer.builder("artverse.agent.routing.latency")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void recordRunOutcome(MangaWorkflowRoute route, String status) {
        meterRegistry.counter("artverse.agent.routing.outcomes",
                "route", route == null ? "UNKNOWN" : route.name(),
                "status", status == null ? "UNKNOWN" : status)
                .increment();
    }
}
