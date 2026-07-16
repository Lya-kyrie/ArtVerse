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
    UUID conversationId,
    BusinessSkillSelection businessSkillSelection
) {
    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables,
                           AgentModelSpec modelSpec, String llmApiKey) {
        this(userId, storyId, chapterId, taskType, messages, variables, modelSpec, llmApiKey,
                null, null, null);
    }

    public AgentRunRequest(String userId, Long storyId, Long chapterId, AgentTaskType taskType,
                           List<AgentMessage> messages, Map<String, Object> variables,
                           AgentModelSpec modelSpec, String llmApiKey, UUID requestId,
                           UUID conversationId) {
        this(userId, storyId, chapterId, taskType, messages, variables, modelSpec, llmApiKey,
                requestId, conversationId, null);
    }
}
