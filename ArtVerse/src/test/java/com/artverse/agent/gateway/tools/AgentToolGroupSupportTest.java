package com.artverse.agent.gateway.tools;

import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.MangaHitlTools;
import com.artverse.application.tools.StoryboardAgentTools;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolGroupSupportTest {

    private MangaContextTools contextTools;
    private StoryboardAgentTools storyboardTools;
    private MangaHitlTools hitlTools;
    private Toolkit toolkit;
    private AgentToolGroupSupport support;

    @BeforeEach
    void setUp() {
        contextTools = mock(MangaContextTools.class);
        storyboardTools = mock(StoryboardAgentTools.class);
        hitlTools = mock(MangaHitlTools.class);
        toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.tool(any())).thenReturn(registration);
        when(registration.group(anyString())).thenReturn(registration);
        support = new AgentToolGroupSupport(contextTools, storyboardTools, hitlTools);
    }

    @Test
    void contextConfigurationActivatesOnlyReadTools() {
        support.configureContext(toolkit);

        verifyGroupCreated(AgentToolGroupSupport.CONTEXT_TOOLS);
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.STORYBOARD_TOOLS), anyString(), eq(true));
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.HITL_TOOLS), anyString(), eq(true));
        verify(toolkit).setActiveGroups(List.of(AgentToolGroupSupport.CONTEXT_TOOLS));
    }

    @Test
    void storyboardConfigurationActivatesAllMangaTools() {
        support.configureStoryboard(toolkit);

        verifyGroupCreated(AgentToolGroupSupport.CONTEXT_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.STORYBOARD_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.HITL_TOOLS);
        verify(toolkit.registration()).tool(storyboardTools);
        verify(toolkit).setActiveGroups(List.of(
                AgentToolGroupSupport.CONTEXT_TOOLS,
                AgentToolGroupSupport.STORYBOARD_TOOLS,
                AgentToolGroupSupport.HITL_TOOLS));
    }

    @Test
    void directorConfigurationExcludesStoryboardMutationTools() {
        support.configureDirector(toolkit);

        verifyGroupCreated(AgentToolGroupSupport.CONTEXT_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.HITL_TOOLS);
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.STORYBOARD_TOOLS), anyString(), eq(true));
        verify(toolkit).setActiveGroups(List.of(
                AgentToolGroupSupport.CONTEXT_TOOLS,
                AgentToolGroupSupport.HITL_TOOLS));
    }

    private void verifyGroupCreated(String group) {
        verify(toolkit).createToolGroup(eq(group), anyString(), eq(true));
    }
}
