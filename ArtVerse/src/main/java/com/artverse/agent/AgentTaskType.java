package com.artverse.agent;

public enum AgentTaskType {
    CHAT("chat"),
    NOVEL("novel"),
    MANGA_DIRECTOR("manga-director"),
    MANGA_REVIEW("manga-review"),
    MANGA_CHAT("manga-chat"),
    MANGA_HITL("manga-hitl");

    private final String sessionSuffix;

    AgentTaskType(String sessionSuffix) {
        this.sessionSuffix = sessionSuffix;
    }

    public String sessionSuffix() {
        return sessionSuffix;
    }
}
