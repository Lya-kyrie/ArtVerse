package com.artverse.application.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

public record MangaWorkflowResult(
        String reply,
        String stepSummary,
        String handoffContext,
        boolean degraded,
        Map<String, Object> attributes
) {
    public MangaWorkflowResult {
        reply = reply == null ? "" : reply;
        stepSummary = stepSummary == null || stepSummary.isBlank() ? reply : stepSummary;
        handoffContext = handoffContext == null || handoffContext.isBlank() ? stepSummary : handoffContext;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static MangaWorkflowResult success(String reply) {
        return success(reply, reply, reply);
    }

    public static MangaWorkflowResult success(String reply, String stepSummary, String handoffContext) {
        return new MangaWorkflowResult(reply, stepSummary, handoffContext, false, Map.of());
    }

    public static MangaWorkflowResult degraded(String reply) {
        return degraded(reply, reply, reply);
    }

    public static MangaWorkflowResult degraded(String reply, String stepSummary, String handoffContext) {
        return new MangaWorkflowResult(reply, stepSummary, handoffContext, true, Map.of());
    }

    public MangaWorkflowResult withAttributes(Map<String, Object> attributes) {
        return new MangaWorkflowResult(reply, stepSummary, handoffContext, degraded, mergeAttributes(attributes));
    }

    public MangaWorkflowResult degradedWithAttributes(Map<String, Object> attributes) {
        return new MangaWorkflowResult(reply, stepSummary, handoffContext, true, mergeAttributes(attributes));
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>(attributes);
        payload.put("reply", reply);
        payload.put("agent_final_response_degraded", degraded);
        return Map.copyOf(payload);
    }

    public static MangaWorkflowResult fromPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return success("");
        }
        String reply = String.valueOf(payload.getOrDefault("reply", ""));
        Object degradedValue = payload.containsKey("agent_final_response_degraded")
                ? payload.get("agent_final_response_degraded")
                : payload.get("degraded");
        boolean degraded = Boolean.TRUE.equals(degradedValue);
        Map<String, Object> attributes = new LinkedHashMap<>(payload);
        attributes.remove("reply");
        attributes.remove("agent_final_response_degraded");
        attributes.remove("degraded");
        return new MangaWorkflowResult(reply, reply, reply, degraded, attributes);
    }

    private Map<String, Object> mergeAttributes(Map<String, Object> incoming) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(attributes);
        if (incoming != null) {
            merged.putAll(incoming);
        }
        return Map.copyOf(merged);
    }
}
