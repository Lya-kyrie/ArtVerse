package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentTaskType;
import com.artverse.application.workflow.MangaReviewMetrics;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class MangaReviewSubagentTracker {

    private final Set<String> expected = AgentTaskType.MANGA_REVIEW.subagentDeclarations().stream()
            .map(declaration -> declaration.getName())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final Set<String> started = new LinkedHashSet<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private final Set<String> active = new LinkedHashSet<>();
    private final Map<String, Long> startedAt = new LinkedHashMap<>();
    private final Map<String, Duration> durations = new LinkedHashMap<>();
    private int maxConcurrency;
    private int failedSpawnCalls;

    boolean observe(AgentEvent event) {
        if (event instanceof ToolResultEndEvent tool
                && "agent_spawn".equals(tool.getToolCallName())
                && tool.getState() != null
                && tool.getState() != ToolResultState.SUCCESS) {
            failedSpawnCalls++;
        }
        String reviewer = reviewerName(event);
        if (reviewer == null) {
            return false;
        }
        if (event instanceof AgentStartEvent) {
            started.add(reviewer);
            active.add(reviewer);
            maxConcurrency = Math.max(maxConcurrency, active.size());
            startedAt.putIfAbsent(reviewer, System.nanoTime());
        } else if (event instanceof AgentEndEvent) {
            completed.add(reviewer);
            active.remove(reviewer);
            Long start = startedAt.get(reviewer);
            if (start != null) {
                durations.put(reviewer, Duration.ofNanos(System.nanoTime() - start));
            }
        }
        return true;
    }

    boolean isSubagentEvent(AgentEvent event) {
        return reviewerName(event) != null;
    }

    Audit finish(MangaReviewMetrics metrics) {
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(started);
        Set<String> timedOut = new LinkedHashSet<>(started);
        timedOut.removeAll(completed);
        metrics.recordStarted(started.size());
        metrics.recordCompleted(completed.size());
        metrics.recordMissing(missing.size());
        metrics.recordTimedOut(timedOut.size());
        metrics.recordFailed(failedSpawnCalls);
        metrics.recordMaxConcurrency(maxConcurrency);
        durations.forEach(metrics::recordDuration);
        return new Audit(expected, started, completed, missing, timedOut, durations,
                maxConcurrency, failedSpawnCalls);
    }

    private String reviewerName(AgentEvent event) {
        if (event instanceof AgentStartEvent start && expected.contains(start.getName())) {
            return start.getName();
        }
        String source = event.getSource();
        return source != null && expected.contains(source) ? source : null;
    }

    record Audit(Set<String> expected, Set<String> started, Set<String> completed,
                 Set<String> missing, Set<String> timedOut, Map<String, Duration> durations,
                 int maxConcurrency, int failedSpawnCalls) {
        Audit {
            expected = Set.copyOf(expected);
            started = Set.copyOf(started);
            completed = Set.copyOf(completed);
            missing = Set.copyOf(missing);
            timedOut = Set.copyOf(timedOut);
            durations = Map.copyOf(durations);
        }

        boolean complete() {
            return missing.isEmpty() && timedOut.isEmpty() && completed.containsAll(expected)
                    && failedSpawnCalls == 0 && maxConcurrency >= expected.size();
        }

        Map<String, Object> attributes() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("review_subagents_expected", expected.size());
            result.put("review_subagents_started", started.size());
            result.put("review_subagents_completed", completed.size());
            result.put("review_subagents_missing", missing.stream().sorted().toList());
            result.put("review_subagents_timed_out", timedOut.stream().sorted().toList());
            result.put("review_subagents_failed_spawn_calls", failedSpawnCalls);
            result.put("review_subagents_max_concurrency", maxConcurrency);
            result.put("review_subagent_durations_ms", durations.entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toMillis())));
            return Map.copyOf(result);
        }
    }
}
