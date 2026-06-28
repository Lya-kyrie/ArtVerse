package com.artverse.application;

import com.artverse.agent.AgentRunEvent;
import com.artverse.domain.MangaAgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaAgentRunEventPublisher {

    private final MangaAgentRunService mangaAgentRunService;
    private final ObjectMapper objectMapper;
    private final AgUiEventFactory agUiEventFactory;
    private final Set<String> activeTextMessages = ConcurrentHashMap.newKeySet();

    public RunEventSink newSink(SseEmitter emitter) {
        return new RunEventSink(emitter);
    }

    public final class RunEventSink {
        private final SseEmitter emitter;

        private RunEventSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        public void sendStatus(MangaAgentRun run, String message, UUID requestId) {
            MangaAgentRunEventPublisher.this.sendStatus(run, emitter, message, requestId);
        }

        public void sendToolEvent(MangaAgentRun run, AgentRunToolStatus.ToolEvent event) {
            MangaAgentRunEventPublisher.this.sendToolEvent(run, emitter, event);
        }

        public void sendRunEvent(MangaAgentRun run, AgentRunEvent event) {
            MangaAgentRunEventPublisher.this.sendRunEvent(run, emitter, event);
        }

        public void sendUserInputRequested(MangaAgentRun run, UUID requestId, AgentUserInputRequest request) {
            MangaAgentRunEventPublisher.this.sendUserInputRequested(run, emitter, requestId, request);
        }


        public void sendDone(MangaAgentRun run, String reply, UUID requestId) {
            MangaAgentRunEventPublisher.this.sendDone(run, emitter, reply, requestId);
        }

        public void sendError(MangaAgentRun run, UUID requestId, String detail) {
            MangaAgentRunEventPublisher.this.sendError(run, emitter, requestId, detail);
        }

        public void complete() {
            MangaAgentRunEventPublisher.this.complete(emitter);
        }
    }

    private void sendStatus(MangaAgentRun run, SseEmitter emitter, String message, UUID requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("requestId", requestId);
        appendRunEvent(run, "status", payload);
        sendAgUi(emitter, agUiEventFactory.runStarted(run, requestId, message));
        sendAgUi(emitter, agUiEventFactory.stateSnapshot(run, requestId, "RUNNING", message));
    }

    private void sendToolEvent(MangaAgentRun run, SseEmitter emitter, AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = toolEventPayload(event);
        appendRunEvent(run, "tool", payload);
        UUID requestId = run == null ? null : run.getRequestId();
        sendAgUi(emitter, agUiEventFactory.toolAudit(requestId, event));
    }

    private void sendRunEvent(MangaAgentRun run, SseEmitter emitter, AgentRunEvent event) {
        Map<String, Object> payload = mangaAgentRunService.toPayload(event);
        if (!"text_delta".equals(event.type())) {
            appendRunEvent(run, "run_event", payload);
        }
        UUID requestId = run == null ? null : run.getRequestId();
        if ("text_delta".equals(event.type())) {
            ensureTextMessageStarted(emitter, requestId);
        }
        sendAgUi(emitter, agUiEventFactory.fromRunEvent(run, requestId, event));
    }

    private void sendUserInputRequested(MangaAgentRun run, SseEmitter emitter, UUID requestId,
                                        AgentUserInputRequest request) {
        Map<String, Object> payload = userInputPayload(requestId, request);
        appendRunEvent(run, "user_input_requested", payload);
        sendAgUi(emitter, agUiEventFactory.userInputRequested(null, requestId, request));
        sendAgUi(emitter, agUiEventFactory.stateSnapshot(run, requestId, "WAITING_USER", request.question()));
        complete(emitter);
    }


    private void sendDone(MangaAgentRun run, SseEmitter emitter, String reply, UUID requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reply", reply);
        payload.put("requestId", requestId);
        appendRunEvent(run, "done", payload);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.runFinished(run, requestId, reply));
        complete(emitter);
    }

    private void sendError(MangaAgentRun run, SseEmitter emitter, UUID requestId, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detail", detail);
        payload.put("requestId", requestId);
        appendRunEvent(run, "error", payload);
        finishTextMessageIfNeeded(emitter, requestId);
        sendAgUi(emitter, agUiEventFactory.runError(requestId, detail));
        complete(emitter);
    }

    private Map<String, Object> toolEventPayload(AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", event.toolName());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        if (event.error() != null && !event.error().isBlank()) {
            payload.put("error", event.error());
        }
        Object saved = event.result().get("saved");
        if (saved != null) {
            payload.put("saved", saved);
        }
        Object scenesCount = event.result().get("scenes_count");
        if (scenesCount != null) {
            payload.put("scenes_count", scenesCount);
        }
        return payload;
    }

    private Map<String, Object> userInputPayload(UUID requestId, AgentUserInputRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("question", request.question());
        payload.put("options", request.options());
        payload.put("allowFreeText", request.allowFreeText());
        payload.put("reason", request.reason());
        return payload;
    }

    private void appendRunEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        if (run == null) {
            return;
        }
        try {
            mangaAgentRunService.appendEvent(run, eventName, payload);
        } catch (Exception e) {
            log.warn("Failed to persist manga agent run event {}", eventName, e);
        }
    }

    private void sendAgUi(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("Failed to send manga agent SSE", e);
        }
    }

    private void ensureTextMessageStarted(SseEmitter emitter, UUID requestId) {
        String key = textMessageKey(emitter, requestId);
        if (key != null && activeTextMessages.add(key)) {
            sendAgUi(emitter, agUiEventFactory.textMessageStart(requestId));
        }
    }

    private void finishTextMessageIfNeeded(SseEmitter emitter, UUID requestId) {
        String key = textMessageKey(emitter, requestId);
        if (key != null && activeTextMessages.remove(key)) {
            sendAgUi(emitter, agUiEventFactory.textMessageEnd(requestId));
        }
    }

    private String textMessageKey(SseEmitter emitter, UUID requestId) {
        if (emitter == null || requestId == null) {
            return null;
        }
        return System.identityHashCode(emitter) + ":" + requestId;
    }

    private void complete(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        String emitterPrefix = System.identityHashCode(emitter) + ":";
        activeTextMessages.removeIf(key -> key.startsWith(emitterPrefix));
        try {
            emitter.complete();
        } catch (Exception e) {
            log.warn("Failed to complete manga agent SSE", e);
        }
    }
}