package com.artverse.agent.gateway.tools;

import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.MangaHitlTools;
import com.artverse.application.tools.StoryboardAgentTools;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class AgentToolGroupSupport {
    static final String CONTEXT_TOOLS = "context-tools";
    static final String STORYBOARD_TOOLS = "storyboard-tools";
    static final String HITL_TOOLS = "hitl-tools";

    private final MangaContextTools mangaContextTools;
    private final StoryboardAgentTools storyboardAgentTools;
    private final MangaHitlTools mangaHitlTools;

    void configureContext(Toolkit toolkit) {
        registerContext(toolkit);
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS));
    }

    void configureStoryboard(Toolkit toolkit) {
        registerContext(toolkit);
        createGroup(toolkit, STORYBOARD_TOOLS,
                "Validated storyboard draft and commit tools.", storyboardAgentTools);
        createGroup(toolkit, HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.", mangaHitlTools);
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS, STORYBOARD_TOOLS, HITL_TOOLS));
    }

    void configureDirector(Toolkit toolkit) {
        registerContext(toolkit);
        createGroup(toolkit, HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.", mangaHitlTools);
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS, HITL_TOOLS));
    }

    private void registerContext(Toolkit toolkit) {
        createGroup(toolkit, CONTEXT_TOOLS,
                "Read-only manga chapter, story, storyboard, and image context tools.", mangaContextTools);
    }

    private void createGroup(Toolkit toolkit, String name, String description, Object tools) {
        toolkit.createToolGroup(name, description, true);
        toolkit.registration().tool(tools).group(name).apply();
    }
}
