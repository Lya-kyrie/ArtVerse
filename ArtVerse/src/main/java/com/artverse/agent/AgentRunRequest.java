package com.artverse.agent;

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
    String llmApiKey,
    UUID requestId,
    UUID conversationId
) {
    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables,
                           AgentModelSpec modelSpec, String llmApiKey) {
        this(userId, storyId, chapterId, taskType, messages, variables, modelSpec, llmApiKey, null, null);
    }
}
