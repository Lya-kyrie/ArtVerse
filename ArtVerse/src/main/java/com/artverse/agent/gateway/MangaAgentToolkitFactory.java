package com.artverse.agent.gateway;

import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.MangaHitlTools;
import com.artverse.application.tools.MangaStoryboardTools;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.SceneService;
import com.artverse.application.StructuredStoryboardService;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MangaAgentToolkitFactory {

    public static final String CONTEXT_TOOLS = "context-tools";
    public static final String STORYBOARD_TOOLS = "storyboard-tools";
    public static final String HITL_TOOLS = "hitl-tools";

    private final MangaImageRepository mangaImageRepository;
    private final SceneService sceneService;
    private final StructuredStoryboardService structuredStoryboardService;
    private final ChapterAccessService chapterAccessService;
    private final GenerationGuardService generationGuardService;
    private final AgentToolAuditService agentToolAuditService;
    private final AgentRunToolStatus agentRunToolStatus;

    public void configureMangaDirector(Toolkit toolkit) {
        MangaToolSupport support = new MangaToolSupport(agentRunToolStatus);

        MangaContextTools contextTools = new MangaContextTools(
                mangaImageRepository, sceneService, chapterAccessService,
                agentToolAuditService, support);
        MangaStoryboardTools storyboardTools = new MangaStoryboardTools(
                sceneService, structuredStoryboardService, chapterAccessService,
                generationGuardService, agentToolAuditService, support);
        MangaHitlTools hitlTools = new MangaHitlTools(agentToolAuditService, support);

        createGroups(toolkit);
        toolkit.registration().tool(contextTools).group(CONTEXT_TOOLS).apply();
        toolkit.registration().tool(storyboardTools).group(STORYBOARD_TOOLS).apply();
        toolkit.registration().tool(hitlTools).group(HITL_TOOLS).apply();
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS, STORYBOARD_TOOLS, HITL_TOOLS));
        toolkit.registerMetaTool();
    }

    private void createGroups(Toolkit toolkit) {
        toolkit.createToolGroup(
                CONTEXT_TOOLS,
                "Read-only manga chapter, story, storyboard, and image context tools.",
                true
        );
        toolkit.createToolGroup(
                STORYBOARD_TOOLS,
                "Storyboard generation and storyboard persistence tools.",
                true
        );
        toolkit.createToolGroup(
                HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.",
                true
        );
    }

}
