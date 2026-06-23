package com.artverse.application;

import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.MangaHitlTools;
import com.artverse.application.tools.MangaStoryboardTools;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MangaAgentToolFactory {

    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final AgentRunToolStatus agentRunToolStatus;

    public Tools create() {
        return create(null, null, null);
    }

    public Tools create(String cozeApiKey, Long chapterId, Long userId) {
        MangaToolSupport support = new MangaToolSupport(agentRunToolStatus, cozeApiKey, chapterId, userId);
        return new Tools(
                new MangaContextTools(
                        mangaImageRepository,
                        sceneService,
                        chapterAccessService,
                        agentToolAuditService,
                        support
                ),
                new MangaStoryboardTools(
                        sceneService,
                        structuredStoryboardService,
                        chapterAccessService,
                        generationGuardService,
                        agentToolAuditService,
                        support
                ),
                new MangaHitlTools(agentToolAuditService, support)
        );
    }

    public record Tools(
            MangaContextTools contextTools,
            MangaStoryboardTools storyboardTools,
            MangaHitlTools hitlTools
    ) {

        public List<Object> all() {
            return List.of(contextTools, storyboardTools, hitlTools);
        }

        public Map<String, Object> getChapterContext(RuntimeContext runtimeContext) {
            return contextTools.getChapterContext(runtimeContext);
        }

        public Map<String, Object> getChapterContext() {
            return contextTools.getChapterContext();
        }

        public Map<String, Object> generateStoryboard(RuntimeContext runtimeContext) {
            return storyboardTools.generateStoryboard(runtimeContext);
        }

        public Map<String, Object> generateStoryboard() {
            return storyboardTools.generateStoryboard();
        }

        public Map<String, Object> saveStoryboard(List<String> scenes, RuntimeContext runtimeContext) {
            return storyboardTools.saveStoryboard(scenes, runtimeContext);
        }

        public Map<String, Object> saveStoryboard(List<String> scenes) {
            return storyboardTools.saveStoryboard(scenes);
        }

        public Map<String, Object> saveStructuredStoryboard(Object pages, RuntimeContext runtimeContext) {
            return storyboardTools.saveStructuredStoryboard(pages, runtimeContext);
        }

        public Map<String, Object> saveStructuredStoryboard(Object pages) {
            return storyboardTools.saveStructuredStoryboard(pages);
        }

        public Map<String, Object> askUser(String question, Object options, Boolean allowFreeText, String reason,
                                           RuntimeContext runtimeContext) {
            return hitlTools.askUser(question, options, allowFreeText, reason, runtimeContext);
        }

        public Map<String, Object> askUser(String question, Object options, Boolean allowFreeText, String reason) {
            return hitlTools.askUser(question, options, allowFreeText, reason);
        }
    }
}
