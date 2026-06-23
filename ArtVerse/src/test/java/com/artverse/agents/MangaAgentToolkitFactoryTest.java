package com.artverse.agents;

import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.ChapterAccessService;
import com.artverse.application.MangaAgentToolFactory;
import com.artverse.application.SceneService;
import com.artverse.application.StructuredStoryboardService;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MangaAgentToolkitFactoryTest {

    @Test
    void registersMangaDirectorToolsIntoAgentScopeToolGroups() {
        MangaAgentToolFactory toolFactory = new MangaAgentToolFactory(
                mock(MangaImageRepository.class),
                mock(SceneService.class),
                mock(StructuredStoryboardService.class),
                new ChapterAccessService(mock(ChapterRepository.class)),
                mock(GenerationGuardService.class),
                new AgentToolAuditService(new AgentRunToolStatus()),
                new AgentRunToolStatus()
        );
        MangaAgentToolkitFactory toolkitFactory = new MangaAgentToolkitFactory(toolFactory);
        Toolkit toolkit = new Toolkit();

        toolkitFactory.configureMangaDirector(toolkit);

        assertThat(toolkit.getActiveGroups()).containsExactlyInAnyOrder(
                MangaAgentToolkitFactory.CONTEXT_TOOLS,
                MangaAgentToolkitFactory.STORYBOARD_TOOLS,
                MangaAgentToolkitFactory.HITL_TOOLS
        );
        assertThat(toolkit.getToolGroup(MangaAgentToolkitFactory.CONTEXT_TOOLS).getTools())
                .contains("get_chapter_context");
        assertThat(toolkit.getToolGroup(MangaAgentToolkitFactory.STORYBOARD_TOOLS).getTools())
                .contains("generate_storyboard", "save_storyboard", "save_structured_storyboard");
        assertThat(toolkit.getToolGroup(MangaAgentToolkitFactory.HITL_TOOLS).getTools())
                .contains("ask_user");
        assertThat(toolkit.getToolNames())
                .containsAll(List.of("get_chapter_context", "generate_storyboard", "save_storyboard",
                        "save_structured_storyboard", "ask_user"));
    }
}
