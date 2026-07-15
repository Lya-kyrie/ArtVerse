package com.artverse.agent;

public record AgentMessage(String role, String content, AgentDataBlock dataBlock) {
    public AgentMessage(String role, String content) {
        this(role, content, null);
    }

    public AgentMessage(String role, AgentDataBlock dataBlock) {
        this(role, "", dataBlock);
    }
}
