package com.artverse.agent;

import java.util.Map;

/**
 * Server-owned data supplied to AgentScope as a DataBlock rather than prompt
 * text. The model may read it, but user-provided text cannot overwrite it.
 */
public record AgentDataBlock(String name, String id, Map<String, Object> payload) {
    public AgentDataBlock {
        name = name == null || name.isBlank() ? "artverse_data" : name;
        id = id == null || id.isBlank() ? name : id;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
