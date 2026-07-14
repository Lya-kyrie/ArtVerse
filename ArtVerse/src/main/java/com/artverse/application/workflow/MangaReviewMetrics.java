package com.artverse.application.workflow;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class MangaReviewMetrics {

    private final MeterRegistry registry;

    public void recordStarted(int count) {
        registry.counter("artverse.agent.review.subagents", "status", "started").increment(count);
    }

    public void recordCompleted(int count) {
        registry.counter("artverse.agent.review.subagents", "status", "completed").increment(count);
    }

    public void recordMissing(int count) {
        registry.counter("artverse.agent.review.subagents", "status", "missing").increment(count);
    }

    public void recordTimedOut(int count) {
        registry.counter("artverse.agent.review.subagents", "status", "timed_out").increment(count);
    }

    public void recordFailed(int count) {
        registry.counter("artverse.agent.review.subagents", "status", "failed").increment(count);
    }

    public void recordMaxConcurrency(int count) {
        registry.summary("artverse.agent.review.subagent.max_concurrency").record(count);
    }

    public void recordDuration(String reviewer, Duration duration) {
        registry.timer("artverse.agent.review.subagent.duration", "reviewer", reviewer)
                .record(duration);
    }
}
