package com.artverse.application;

import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MangaAgentToolFactory {

    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;

    public Object create(String cozeApiKey, Long chapterId, Long userId) {
        return new Tools(cozeApiKey, chapterId, userId);
    }

    @RequiredArgsConstructor
    public class Tools {

        private final String cozeApiKey;
        private final Long chapterId;
        private final Long userId;

        @Tool(
                name = "get_chapter_context",
                description = "Read the current chapter, story settings, source text, storyboard status, and generated image status.",
                readOnly = true
        )
        @Transactional(readOnly = true)
        public Map<String, Object> getChapterContext() {
            return agentToolAuditService.around("get_chapter_context", userId, chapterId, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                List<String> scenes = sceneService.getScenes(chapterId);
                List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapterId);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("story_title", chapter.getStory().getTitle());
                result.put("chapter_number", chapter.getChapterNumber());
                result.put("chapter_display_name", chapterDisplayName(chapter));
                result.put("image_count", chapter.getImageCount());
                result.put("color_mode", chapter.getColorMode().name().toLowerCase());
                result.put("manga_style", chapter.getStory().getMangaStyle());
                result.put("has_source_content", !chapter.novelContentOrJoinedMessages().isBlank());
                result.put("source_excerpt", excerpt(chapter.novelContentOrJoinedMessages(), 1200));
                result.put("scenes_count", scenes.size());
                result.put("scenes", scenes);
                result.put("generated_images", images.stream()
                        .map(image -> Map.of(
                                "image_number", image.getImageNumber(),
                                "image_path", image.getImagePath(),
                                "has_prompt", image.getPrompt() != null && !image.getPrompt().isBlank()
                        ))
                        .toList());
                return result;
            });
        }

        @Tool(
                name = "generate_storyboard",
                description = "Generate storyboard scenes from the chapter source content and save them to the chapter.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> generateStoryboard() {
            return agentToolAuditService.around("generate_storyboard", userId, chapterId, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                return generationGuardService.executeSceneGeneration(
                        userId,
                        chapterId,
                        () -> {
                            List<String> scenes = sceneService.generateScenes(chapterId, cozeApiKey);
                            return Map.of(
                                    "chapter_display_name", chapterDisplayName(chapter),
                                    "scenes_count", scenes.size(),
                                    "scenes", scenes
                            );
                        }
                );
            });
        }

        @Tool(
                name = "save_storyboard",
                description = "Save edited storyboard scenes to the chapter.",
                concurrencySafe = false
        )
        @Transactional
        public Map<String, Object> saveStoryboard(
                @ToolParam(name = "scenes", description = "Complete storyboard scene list") List<String> scenes) {
            return agentToolAuditService.around("save_storyboard", userId, chapterId, () -> {
                Chapter chapter = chapterAccessService.requireVisible(chapterId, userId);
                List<String> updated = sceneService.updateScenes(chapterId, scenes);
                return Map.of(
                        "chapter_display_name", chapterDisplayName(chapter),
                        "scenes_count", updated.size(),
                        "scenes", updated
                );
            });
        }
    }

    private String chapterDisplayName(Chapter chapter) {
        if (chapter.getDisplayTitle() != null && !chapter.getDisplayTitle().isBlank()) {
            return chapter.getDisplayTitle();
        }
        return "第" + chapter.getChapterNumber() + "话";
    }

    private String excerpt(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }
}
