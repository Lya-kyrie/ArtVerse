package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
class StoryChatReadToolsStrategy implements AgentToolConfigurationStrategy {
    private final AgentToolGroupSupport toolGroups;

    @Override
    public Set<AgentTaskType> supportedTaskTypes() {
        return Set.of(AgentTaskType.STORY_CHAT_READ);
    }

    @Override
    public void configure(Toolkit toolkit) {
        toolGroups.configureStoryChatRead(toolkit);
    }
}
