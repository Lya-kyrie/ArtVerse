package com.artverse.agents;

import com.artverse.application.MangaAgentToolFactory;
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

    private final MangaAgentToolFactory mangaAgentToolFactory;

    public void configureMangaDirector(Toolkit toolkit, List<String> activeGroups) {
        MangaAgentToolFactory.Tools tools = mangaAgentToolFactory.create();
        createGroups(toolkit);
        toolkit.registration().tool(tools.contextTools()).group(CONTEXT_TOOLS).apply();
        toolkit.registration().tool(tools.storyboardTools()).group(STORYBOARD_TOOLS).apply();
        toolkit.registration().tool(tools.hitlTools()).group(HITL_TOOLS).apply();
        toolkit.setActiveGroups(normalizeActiveGroups(activeGroups));
        toolkit.registerMetaTool();
    }

    public List<String> activeGroupsForDirector() {
        return List.of(CONTEXT_TOOLS, STORYBOARD_TOOLS, HITL_TOOLS);
    }

    public void activateForRequest(Toolkit toolkit, List<String> activeGroups) {
        if (toolkit == null) {
            return;
        }
        toolkit.setActiveGroups(normalizeActiveGroups(activeGroups));
    }

    private List<String> normalizeActiveGroups(List<String> activeGroups) {
        return (activeGroups == null || activeGroups.isEmpty()) ? activeGroupsForDirector() : List.copyOf(activeGroups);
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
