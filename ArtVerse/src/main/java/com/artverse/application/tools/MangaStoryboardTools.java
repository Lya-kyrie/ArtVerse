package com.artverse.application.tools;

import com.artverse.agents.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.application.StructuredStoryboardService;
import com.artverse.domain.Chapter;
import com.artverse.guard.GenerationGuardService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MangaStoryboardTools {

    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final MangaToolSupport support;

    @Tool(
            name = "generate_storyboard",
            description = "Generate storyboard scenes from the chapter source content and save them to the chapter.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> generateStoryboard(RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("generate_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
            return generationGuardService.executeSceneGeneration(
                    context.userId(),
                    context.chapterId(),
                    () -> {
                        List<String> scenes = sceneService.generateScenes(context.chapterId(), context.cozeApiKey());
                        return Map.of(
                                "chapter_display_name", support.chapterDisplayName(chapter),
                                "saved", true,
                                "changed", true,
                                "scenes_count", scenes.size(),
                                "scenes", scenes
                        );
                    }
            );
        });
    }

    public Map<String, Object> generateStoryboard() {
        return generateStoryboard(null);
    }

    @Tool(
            name = "save_storyboard",
            description = "Save edited storyboard scenes to the chapter.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> saveStoryboard(
            @ToolParam(name = "scenes", description = "Complete storyboard scene list") List<String> scenes,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("save_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
            List<String> updated = sceneService.updateScenes(context.chapterId(), scenes);
            return Map.of(
                    "chapter_display_name", support.chapterDisplayName(chapter),
                    "saved", true,
                    "changed", true,
                    "scenes_count", updated.size(),
                    "scenes", updated
            );
        });
    }

    public Map<String, Object> saveStoryboard(List<String> scenes) {
        return saveStoryboard(scenes, null);
    }

    @Tool(
            name = "save_structured_storyboard",
            description = "Save storyboard pages as structured page/panel data. Input may be a list of pages or an object with pages. Each page must contain 4-6 panels.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> saveStructuredStoryboard(
            @ToolParam(name = "pages", description = "Storyboard pages with panels") Object pages,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("save_structured_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
            List<String> scenes = structuredStoryboardService.normalize(pages, chapter.getImageCount());
            List<String> updated = sceneService.updateScenes(context.chapterId(), scenes);
            return Map.of(
                    "chapter_display_name", support.chapterDisplayName(chapter),
                    "saved", true,
                    "changed", true,
                    "scenes_count", updated.size(),
                    "scenes", updated
            );
        });
    }

    public Map<String, Object> saveStructuredStoryboard(Object pages) {
        return saveStructuredStoryboard(pages, null);
    }
}
