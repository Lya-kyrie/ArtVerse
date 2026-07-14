package com.artverse.agent.gateway;


import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentRunRequest;

import com.artverse.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentScopeRuntimeContextFactory {


    public RuntimeContext create(AgentRunRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(createSessionId(request))
                .userId(request.userId());
        if (request.taskType() != null && request.taskType().isMangaExecutionTask()) {
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

    static String createSessionId(AgentRunRequest request) {
        return String.join("-",
                "u", safeSegment(request.userId()),
                "story", safeSegment(request.storyId()),
                "chapter", safeSegment(request.chapterId()),
                "conv", safeSegment(request.conversationId()),
                safeSegment(request.taskType() == null ? "unknown" : request.taskType().sessionSuffix())
        );
    }

    public static String safeSegment(Object value) {
        if (value == null) {
            return "none";
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        if (normalized.isBlank()) {
            return "none";
        }
        String safe = normalized.replaceAll("[^a-z0-9._-]", "-")
                .replace("..", "-");
        return safe.isBlank() ? "none" : safe;
    }

}
