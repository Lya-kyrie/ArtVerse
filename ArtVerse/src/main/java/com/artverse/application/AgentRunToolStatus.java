package com.artverse.application;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class AgentRunToolStatus {

    private static final Set<String> MUTATING_TOOLS = Set.of(
            "generate_storyboard",
            "save_storyboard",
            "save_structured_storyboard"
    );

    private final ConcurrentMap<ScopeKey, CopyOnWriteArrayList<RunState>> activeRuns = new ConcurrentHashMap<>();

    public RunScope start(Long userId, Long chapterId, UUID requestId) {
        return start(userId, chapterId, requestId, null);
    }

    public RunScope start(Long userId, Long chapterId, UUID requestId, Consumer<ToolEvent> listener) {
        ScopeKey key = new ScopeKey(userId, chapterId);
        RunState state = new RunState(userId, chapterId, requestId, listener);
        activeRuns.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(state);
        return new RunScope(key, state);
    }

    public void recordSucceeded(String toolName, Long userId, Long chapterId, long durationMs,
                                Map<String, Object> result) {
        record(new ToolEvent(toolName, true, durationMs, null, result == null ? Map.of() : new LinkedHashMap<>(result)),
                userId, chapterId);
    }

    public void recordFailed(String toolName, Long userId, Long chapterId, long durationMs, String error) {
        record(new ToolEvent(toolName, false, durationMs, error, Map.of()), userId, chapterId);
    }

    private void record(ToolEvent event, Long userId, Long chapterId) {
        CopyOnWriteArrayList<RunState> states = activeRuns.get(new ScopeKey(userId, chapterId));
        if (states == null) {
            return;
        }
        states.forEach(state -> state.add(event));
    }

    public final class RunScope implements AutoCloseable {
        private final ScopeKey key;
        private final RunState state;
        private boolean closed;

        private RunScope(ScopeKey key, RunState state) {
            this.key = key;
            this.state = state;
        }

        public RunState state() {
            return state;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            CopyOnWriteArrayList<RunState> states = activeRuns.get(key);
            if (states == null) {
                return;
            }
            states.remove(state);
            if (states.isEmpty()) {
                activeRuns.remove(key, states);
            }
        }
    }

    public static final class RunState {
        private final Long userId;
        private final Long chapterId;
        private final UUID requestId;
        private final Consumer<ToolEvent> listener;
        private final CopyOnWriteArrayList<ToolEvent> events = new CopyOnWriteArrayList<>();

        private RunState(Long userId, Long chapterId, UUID requestId, Consumer<ToolEvent> listener) {
            this.userId = userId;
            this.chapterId = chapterId;
            this.requestId = requestId;
            this.listener = listener;
        }

        private void add(ToolEvent event) {
            events.add(event);
            if (listener != null) {
                listener.accept(event);
            }
        }

        public Long userId() {
            return userId;
        }

        public Long chapterId() {
            return chapterId;
        }

        public UUID requestId() {
            return requestId;
        }

        public List<ToolEvent> events() {
            return List.copyOf(events);
        }

        public List<ToolEvent> successfulMutatingEvents() {
            return events.stream()
                    .filter(event -> event.succeeded() && MUTATING_TOOLS.contains(event.toolName()))
                    .toList();
        }

        public boolean hasSuccessfulMutatingTool() {
            return !successfulMutatingEvents().isEmpty();
        }

        public ToolEvent lastSuccessfulMutatingEvent() {
            List<ToolEvent> successful = successfulMutatingEvents();
            return successful.isEmpty() ? null : successful.get(successful.size() - 1);
        }
    }

    public record ToolEvent(String toolName,
                            boolean succeeded,
                            long durationMs,
                            String error,
                            Map<String, Object> result) {
    }

    private record ScopeKey(Long userId, Long chapterId) {
    }
}
