package com.artverse.application.workflow;

import com.artverse.application.CharacterProfileService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaImage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.persistence.MangaImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class MangaWorkflowContextAssembler {

    private static final int EXCERPT_LIMIT = 1800;

    private final MangaAgentConversationService mangaAgentConversationService;
    private final MangaImageRepository mangaImageRepository;
    private final CharacterProfileService characterProfileService;
    private final MangaToolSupport mangaToolSupport;
    private final MangaWorkflowContextPolicy contextPolicy;

    public MangaWorkflowContextSnapshot assemble(MangaAgentConversation conversation, String userMessage,
                                                 RoutingDecision decision) {
        Chapter chapter = conversation.getChapter();
        Story story = chapter.getStory();
        List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapter.getId());
        Map<String, Object> characterProfile = characterProfileService.resolveEffective(chapter.getId());
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(conversation);
        String sourceExcerpt = excerpt(chapter.novelContentOrJoinedMessages(), EXCERPT_LIMIT);
        String storyboardExcerpt = excerpt(chapter.getScenesText(), EXCERPT_LIMIT);
        String characterSummary = excerpt(String.valueOf(characterProfile.getOrDefault("content", "")), EXCERPT_LIMIT);
        String conversationSummary = summarizeConversation(history, userMessage);
        List<String> requiredFields = contextPolicy.requiredFields(decision);
        MangaWorkflowRoute route = decision == null ? MangaWorkflowRoute.CONVERSATION : decision.route();

        return new MangaWorkflowContextSnapshot(
                story.getId(),
                chapter.getId(),
                story.getTitle(),
                chapterDisplayName(chapter),
                story.getMangaStyle(),
                countScenes(chapter.getScenesText()),
                images == null ? 0 : images.size(),
                sourceExcerpt,
                storyboardExcerpt,
                characterSummary,
                conversationSummary,
                route,
                hashContext(story.getId(), chapter.getId(), story.getTitle(), chapterDisplayName(chapter),
                        story.getMangaStyle(), sourceExcerpt, storyboardExcerpt, characterSummary, conversationSummary,
                        countScenes(chapter.getScenesText()), images == null ? 0 : images.size(), requiredFields),
                requiredFields,
                warningsFor(sourceExcerpt, storyboardExcerpt, images, requiredFields)
        );
    }

    public Map<String, Object> summary(MangaWorkflowContextSnapshot snapshot) {
        return Map.of(
                "storyTitle", snapshot.storyTitle(),
                "chapterDisplayName", snapshot.chapterDisplayName(),
                "sceneCount", snapshot.sceneCount(),
                "imageCount", snapshot.imageCount(),
                "contextHash", snapshot.contextHash(),
                "requiredFields", snapshot.requiredFields(),
                "warnings", snapshot.warnings()
        );
    }

    private List<String> warningsFor(String sourceExcerpt, String storyboardExcerpt,
                                     List<MangaImage> images, List<String> requiredFields) {
        Set<String> warnings = new LinkedHashSet<>();
        if (sourceExcerpt == null || sourceExcerpt.isBlank()) {
            warnings.add("chapter_source_missing");
        }
        if (storyboardExcerpt == null || storyboardExcerpt.isBlank()) {
            warnings.add("storyboard_missing");
        }
        if (images == null || images.isEmpty()) {
            warnings.add("no_generated_images");
        }
        for (String field : requiredFields) {
            if ("chapter_source_excerpt".equals(field) && (sourceExcerpt == null || sourceExcerpt.isBlank())) {
                warnings.add("required_context_missing:chapter_source_excerpt");
            }
            if ("storyboard_excerpt".equals(field) && (storyboardExcerpt == null || storyboardExcerpt.isBlank())) {
                warnings.add("required_context_missing:storyboard_excerpt");
            }
        }
        warnings.add("knowledge_recall_missing");
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
        return mangaToolSupport.chapterDisplayName(chapter);
    }

    private String excerpt(String text, int limit) {
        return mangaToolSupport.excerpt(text, limit);
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

    private String hashContext(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                output.append(String.format("%02x", b));
            }
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to hash workflow context snapshot", error);
        }
    }
}
