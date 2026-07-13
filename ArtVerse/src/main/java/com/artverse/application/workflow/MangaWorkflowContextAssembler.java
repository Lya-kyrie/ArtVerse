package com.artverse.application.workflow;

import com.artverse.application.CharacterProfileService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaImage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.persistence.MangaImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MangaWorkflowContextAssembler {

    private static final int EXCERPT_LIMIT = 1800;

    private final MangaAgentConversationService mangaAgentConversationService;
    private final MangaImageRepository mangaImageRepository;
    private final CharacterProfileService characterProfileService;

    public MangaWorkflowContextSnapshot assemble(MangaAgentConversation conversation, String userMessage) {
        Chapter chapter = conversation.getChapter();
        Story story = chapter.getStory();
        List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapter.getId());
        Map<String, Object> characterProfile = characterProfileService.resolveEffective(chapter.getId());
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(conversation);

        return new MangaWorkflowContextSnapshot(
                story.getId(),
                chapter.getId(),
                story.getTitle(),
                chapterDisplayName(chapter),
                story.getMangaStyle(),
                countScenes(chapter.getScenesText()),
                images == null ? 0 : images.size(),
                excerpt(chapter.novelContentOrJoinedMessages(), EXCERPT_LIMIT),
                excerpt(String.valueOf(characterProfile.getOrDefault("content", "")), EXCERPT_LIMIT),
                summarizeConversation(history, userMessage),
                MangaWorkflowRoute.DIRECTOR,
                warningsFor(chapter, images)
        );
    }

    public Map<String, Object> summary(MangaWorkflowContextSnapshot snapshot) {
        return Map.of(
                "storyTitle", snapshot.storyTitle(),
                "chapterDisplayName", snapshot.chapterDisplayName(),
                "sceneCount", snapshot.sceneCount(),
                "imageCount", snapshot.imageCount(),
                "warnings", snapshot.warnings()
        );
    }

    private List<String> warningsFor(Chapter chapter, List<MangaImage> images) {
        ArrayList<String> warnings = new ArrayList<>();
        if (chapter.novelContentOrJoinedMessages() == null || chapter.novelContentOrJoinedMessages().isBlank()) {
            warnings.add("chapter_source_missing");
        }
        if (images == null || images.isEmpty()) {
            warnings.add("no_generated_images");
        }
        return List.copyOf(warnings);
    }

    private String summarizeConversation(List<MangaAgentMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        long startIndex = Math.max(0, history.size() - 8L);
        history.stream()
                .filter(item -> item.getRole() == MessageRole.USER || item.getRole() == MessageRole.ASSISTANT)
                .skip(startIndex)
                .forEach(item -> sb.append(item.getRole().name().toLowerCase()).append(": ")
                        .append(excerpt(item.getContent(), 220)).append("\n"));
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append("user: ").append(excerpt(userMessage, 220)).append("\n");
        }
        return sb.toString().trim();
    }

    private String chapterDisplayName(Chapter chapter) {
        if (chapter.getDisplayTitle() != null && !chapter.getDisplayTitle().isBlank()) {
            return chapter.getDisplayTitle();
        }
        return "Chapter " + chapter.getChapterNumber();
    }

    private String excerpt(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private int countScenes(String scenesText) {
        if (scenesText == null || scenesText.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < scenesText.length(); i++) {
            if (scenesText.charAt(i) == '"') {
                count++;
            }
        }
        return Math.max(1, count / 2);
    }
}
