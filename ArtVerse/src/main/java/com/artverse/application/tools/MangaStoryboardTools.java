package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.application.StructuredStoryboardService;
import com.artverse.application.ToolIdempotencyService;
import com.artverse.domain.Chapter;
import com.artverse.guard.GenerationGuardService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MangaStoryboardTools {

    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final ToolIdempotencyService idempotencyService;
    private final MangaToolSupport support;
    private final ObjectMapper objectMapper;

    @Tool(
            name = "generate_storyboard",
            description = "Generate storyboard scenes from the chapter source content and save them to the chapter.",
            concurrencySafe = false
    )
    @Transactional
    public Map<String, Object> generateStoryboard(RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("generate_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            return idempotencyService.execute(context.requestId(), "generate_storyboard", "no-arguments", () -> {
                Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                support.requireDestructiveWriteConfirmation(context, chapter, "generate_storyboard_overwrite");
                return generationGuardService.executeSceneGeneration(
                        context.userId(), context.chapterId(), () -> {
                            List<String> scenes = sceneService.generateScenes(context.chapterId(), context.cozeApiKey());
                            return buildResultMap(chapter, scenes);
                        });
            });
        });
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
        String inputHash = contentHash(scenes);
        return agentToolAuditService.around("save_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            return idempotencyService.execute(context.requestId(), "save_storyboard", inputHash, () -> {
                Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                support.requireDestructiveWriteConfirmation(context, chapter, "save_storyboard_overwrite");
                List<String> updated = sceneService.updateScenes(context.chapterId(), scenes);
                return buildResultMap(chapter, updated);
            });
        });
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
        String inputHash = contentHash(pages);
        return agentToolAuditService.around("save_structured_storyboard", context.userId(), context.chapterId(), runtimeContext, () -> {
            return idempotencyService.execute(context.requestId(), "save_structured_storyboard", inputHash, () -> {
                Chapter chapter = chapterAccessService.requireVisible(context.chapterId(), context.userId());
                support.requireDestructiveWriteConfirmation(context, chapter, "save_structured_storyboard_overwrite");
                List<String> normalized = structuredStoryboardService.normalize(pages, chapter.getImageCount());
                List<String> updated = sceneService.updateScenes(context.chapterId(), normalized);
                return buildResultMap(chapter, updated);
            });
        });
    }

    private Map<String, Object> buildResultMap(Chapter chapter, List<String> scenes) {
        return Map.of(
                "chapter_display_name", support.chapterDisplayName(chapter),
                "saved", true,
                "changed", true,
                "scenes_count", scenes.size(),
                "scenes", scenes
        );
    }

    private String contentHash(Object pages) {
        if (pages == null) {
            return ToolIdempotencyService.sha256("pages:null");
        }
        try {
            String json = objectMapper.writeValueAsString(pages);
            return ToolIdempotencyService.sha256("pages:" + json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize pages for idempotency hash; falling back to type+toString", e);
            return ToolIdempotencyService.sha256("pages:" + pages.getClass().getName() + ":" + pages);
        }
    }
}
