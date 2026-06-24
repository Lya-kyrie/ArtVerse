package com.artverse.application.workflow;

import java.util.Map;

public record MangaWorkflowResult(
        String reply,
        boolean degraded
) {
    public static MangaWorkflowResult success(String reply) {
        return new MangaWorkflowResult(reply, false);
    }

    public static MangaWorkflowResult degraded(String reply) {
        return new MangaWorkflowResult(reply, true);
    }

    public Map<String, Object> toPayload() {
        return Map.of(
                "reply", reply == null ? "" : reply,
                "agent_final_response_degraded", degraded
        );
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
        return degraded ? degraded(reply) : success(reply);
    }
}
