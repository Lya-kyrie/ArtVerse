package com.artverse.application.tools;

import com.artverse.agents.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MangaContextTools {

    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final ChapterAccessService chapterAccessService;
    private final AgentToolAuditService agentToolAuditService;
    private final MangaToolSupport support;

    @Tool(
            name = "get_chapter_context",
            description = "Read the current chapter, story settings, source text, storyboard status, and generated image status.",
            readOnly = true
    )
    @Transactional(readOnly = true)
    public Map<String, Object> getChapterContext(RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("get_chapter_context", context.userId(), context.chapterId(), runtimeContext, () -> {
            Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
            List<String> scenes = sceneService.getScenes(context.chapterId());
            List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(context.chapterId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("story_title", chapter.getStory().getTitle());
            result.put("chapter_number", chapter.getChapterNumber());
            result.put("chapter_display_name", support.chapterDisplayName(chapter));
            result.put("image_count", chapter.getImageCount());
            result.put("color_mode", chapter.getColorMode().name().toLowerCase());
            result.put("manga_style", chapter.getStory().getMangaStyle());
            result.put("has_source_content", !chapter.novelContentOrJoinedMessages().isBlank());
            result.put("source_excerpt", support.excerpt(chapter.novelContentOrJoinedMessages(), 1200));
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

    public Map<String, Object> getChapterContext() {
        return getChapterContext(null);
    }
}
