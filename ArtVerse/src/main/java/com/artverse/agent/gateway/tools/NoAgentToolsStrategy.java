package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class NoAgentToolsStrategy implements AgentToolConfigurationStrategy {
    private static final Set<AgentTaskType> TASK_TYPES = Set.of(
            AgentTaskType.CHAT, AgentTaskType.NOVEL, AgentTaskType.MANGA_ROUTER);

    @Override
    public Set<AgentTaskType> supportedTaskTypes() {
        return TASK_TYPES;
    }

    @Override
    public void configure(Toolkit toolkit) {
        // These agents intentionally have no ArtVerse business tools.
    }
}
