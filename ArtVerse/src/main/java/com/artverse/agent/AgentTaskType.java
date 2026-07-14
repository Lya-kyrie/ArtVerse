package com.artverse.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.util.List;

public enum AgentTaskType {
    CHAT("chat"),
    NOVEL("novel"),
    MANGA_ROUTER("manga-router"),
    MANGA_CONVERSATION("manga-conversation"),
    MANGA_CREATIVE("manga-creative"),
    MANGA_STORYBOARD("manga-storyboard"),
    MANGA_REVIEW("manga-review", MangaReviewSubagentDeclarations.all()),
    MANGA_DIRECTOR("manga-director"),
    KNOWLEDGE_EXTRACTION("knowledge-extraction");

    private final String sessionSuffix;
    private final List<SubagentDeclaration> subagentDeclarations;

    AgentTaskType(String sessionSuffix) {
        this(sessionSuffix, List.of());
    }

    AgentTaskType(String sessionSuffix, List<SubagentDeclaration> subagentDeclarations) {
        this.sessionSuffix = sessionSuffix;
        this.subagentDeclarations = List.copyOf(subagentDeclarations);
    }

    public String sessionSuffix() {
        return sessionSuffix;
    }

    public List<SubagentDeclaration> subagentDeclarations() {
        return subagentDeclarations;
    }

    public boolean isMangaExecutionTask() {
        return this == MANGA_CONVERSATION
                || this == MANGA_CREATIVE
                || this == MANGA_STORYBOARD
                || this == MANGA_REVIEW
                || this == MANGA_DIRECTOR;
    }
}
