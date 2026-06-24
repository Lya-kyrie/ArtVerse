package com.artverse.agents;

import com.artverse.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentScopeRuntimeContextFactory {

    private final AgentSessionIdFactory agentSessionIdFactory;

    public RuntimeContext create(AgentRunRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(agentSessionIdFactory.create(request))
                .userId(request.userId());
        if (request.requestId() != null) {
            builder.put(AgentRunContext.class, new AgentRunContext(request.requestId()));
        }
        if (request.taskType() == AgentTaskType.MANGA_DIRECTOR) {
            builder.put(MangaAgentRuntimeContext.class, new MangaAgentRuntimeContext(
                    parseUserIdForTool(request.userId()),
                    request.storyId(),
                    request.chapterId(),
                    request.conversationId(),
                    request.requestId(),
                    String.valueOf(request.variables().getOrDefault("coze_api_key", ""))
            ));
        }
        return builder.build();
    }

    static Long parseUserIdForTool(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (Exception e) {
            throw new BusinessException(400, "Invalid agent user id");
        }
    }
}
