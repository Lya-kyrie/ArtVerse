package com.artverse.application.workflow;

import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.domain.MangaAgentRun;

import java.util.LinkedHashMap;
import java.util.Map;

public record MangaWorkflowStreamContext(
        MangaAgentRun run,
        MangaAgentRunEventPublisher.RunEventSink sink,
        Map<String, Object> eventContext,
        boolean suppressTextDeltas
) {
    public MangaWorkflowStreamContext(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink) {
        this(run, sink, Map.of(), false);
    }

    public MangaWorkflowStreamContext {
        eventContext = eventContext == null ? Map.of() : Map.copyOf(eventContext);
    }

    public void sendRunEvent(com.artverse.agent.AgentRunEvent event) {
        if (suppressTextDeltas && "text_delta".equals(event.type())) {
            return;
        }
        sink.sendRunEvent(run, event.withData(eventContext));
    }

    public MangaWorkflowStreamContext forStep(String planId, int step, MangaWorkflowRoute route) {
        LinkedHashMap<String, Object> context = new LinkedHashMap<>(eventContext);
        context.put("planId", planId);
        context.put("step", step);
        context.put("route", route.name());
        context.put("agentName", route.name().toLowerCase() + "-agent");
        return new MangaWorkflowStreamContext(run, sink, context, true);
    }
}
