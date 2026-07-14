package com.artverse.application.workflow;

import java.util.List;

public record MangaWorkflowContextSnapshot(
        Long storyId,
        Long chapterId,
        String storyTitle,
        String chapterDisplayName,
        String storyStyle,
        int sceneCount,
        int imageCount,
        String sourceExcerpt,
        String storyboardExcerpt,
        String characterSummary,
        String conversationSummary,
        MangaWorkflowRoute route,
        String contextHash,
        List<String> requiredFields,
        List<String> warnings
) {
}
