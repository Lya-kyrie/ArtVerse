package com.artverse.application.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The only storyboard mutation surface registered in an AgentScope Toolkit.
 * Compatibility methods remain available to legacy application callers but are
 * deliberately absent here, so a model can never discover or invoke them.
 */
@Component
@RequiredArgsConstructor
public class StoryboardAgentTools {

    private final MangaStoryboardTools storyboardTools;

    @Tool(
            name = "draft_structured_storyboard",
            description = "Create and validate a storyboard draft without changing the chapter.",
            concurrencySafe = false
    )
    public Map<String, Object> draftStructuredStoryboard(
            @ToolParam(name = "pages", description = "Storyboard pages with 4-6 panels per page") Object pages,
            RuntimeContext runtimeContext) {
        return storyboardTools.draftStructuredStoryboard(pages, runtimeContext);
    }

    @Tool(
            name = "commit_storyboard",
            description = "Commit one validated storyboard artifact. This is the only chapter write operation.",
            concurrencySafe = false
    )
    public Map<String, Object> commitStoryboard(
            @ToolParam(name = "artifact_id", description = "Validated storyboard artifact UUID") String artifactId,
            RuntimeContext runtimeContext) {
        return storyboardTools.commitStoryboard(artifactId, runtimeContext);
    }
}
