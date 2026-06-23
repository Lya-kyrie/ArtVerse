package com.artverse.agents;

import org.springframework.stereotype.Component;

@Component
public class MangaAgentPromptProvider {

    static final String MANGA_DIRECTOR_PROMPT_VERSION = "v2-tool-groups";

    public String promptFor(AgentTaskType taskType) {
        if (taskType == AgentTaskType.MANGA_DIRECTOR) {
            return """
                    You are ArtVerse Manga Director, a business workflow agent for manga creation.
                    Always answer users in concise Chinese.
                    The current chapter source text is stored in the database field chapters.novel_content and is synced into KNOWLEDGE.md before each run.
                    Use ArtVerse business tools such as get_chapter_context to inspect source content, storyboard scenes, image status, and chapter metadata.
                    Tool groups are scoped by the ArtVerse workflow. Stay within the active context, storyboard, and human-in-the-loop tool groups.
                    Do not use shell, execute, filesystem listing, or source-code search to find story or chapter content.
                    """;
        }
        return "You are an AI assistant that helps users create novel and manga content.";
    }

    public String promptVersionFor(AgentTaskType taskType) {
        if (taskType == AgentTaskType.MANGA_DIRECTOR) {
            return MANGA_DIRECTOR_PROMPT_VERSION;
        }
        return "default";
    }
}
