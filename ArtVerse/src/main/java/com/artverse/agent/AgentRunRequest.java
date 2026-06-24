package com.artverse.agents;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentRunRequest(
    String userId,
    Long storyId,
    Long chapterId,
    AgentTaskType taskType,
    List<AgentMessage> messages,
    Map<String, Object> variables,
    AgentModelSpec modelSpec,
    String userApiKey,
    UUID requestId,
    UUID conversationId,
    List<String> activeToolGroups
) {
    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables) {
        this(userId, storyId, chapterId, taskType, messages, variables, null, null, null, null, List.of());
    }

    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables, String userApiKey) {
        this(userId, storyId, chapterId, taskType, messages, variables, null, userApiKey, null, null, List.of());
    }

    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables,
                           AgentModelSpec modelSpec, String userApiKey) {
        this(userId, storyId, chapterId, taskType, messages, variables, modelSpec, userApiKey, null, null, List.of());
    }
}
