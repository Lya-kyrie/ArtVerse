package com.artverse.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.artverse.agent.gateway.AgentScopeRuntimeContextFactory;
import io.agentscope.core.agent.RuntimeContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceService {

    private final @Qualifier("agentScopeWorkspace") Path workspaceRoot;
    private final PostgresAgentWorkspaceStore workspaceStore;

    public Path workspaceFor(AgentRunRequest request) {
        return workspaceFor(request.userId(), request.storyId(), request.conversationId());
    }

    public Path workspaceFor(String userId, Long storyId) {
        return workspaceFor(userId, storyId, null);
    }

    public Path workspaceFor(String userId, Long storyId, Object conversationId) {
        List<String> namespace = namespaceFor(userId, storyId, conversationId);
        initialize(namespace);
        // Harness still requires a logical workspace path for path resolution,
        // while all managed contents are served by RemoteFilesystem.
        return workspaceRoot.toAbsolutePath().normalize();
    }

    public void writeKnowledge(String userId, Long storyId, String content) {
        writeKnowledge(userId, storyId, null, content);
    }

    public void writeKnowledge(String userId, Long storyId, Object conversationId, String content) {
        List<String> namespace = namespaceFor(userId, storyId, conversationId);
        initialize(namespace);
        workspaceStore.put(namespace, "KNOWLEDGE.md", fileValue(content == null ? "" : content));
    }

    private void initialize(List<String> namespace) {
        writeIfAbsent(namespace, "AGENTS.md", defaultAgentsMd());
        writeIfAbsent(namespace, "KNOWLEDGE.md",
                "# Story Knowledge\n\nNo story context has been synced yet.\n");
        writeIfAbsent(namespace, "MEMORY.md", "# Long Term Memory\n\n");
    }

    private void writeIfAbsent(List<String> namespace, String key, String content) {
        if (workspaceStore.get(namespace, key) == null) {
            workspaceStore.putIfVersion(namespace, key, fileValue(content), 0);
        }
    }

    public static List<String> namespaceFor(RuntimeContext context) {
        MangaAgentRuntimeContext manga = context == null ? null : context.get(MangaAgentRuntimeContext.class);
        if (manga != null) {
            return namespaceFor(String.valueOf(manga.userId()), manga.storyId(), manga.conversationId());
        }
        return List.of(
                "artverse",
                "user-" + AgentScopeRuntimeContextFactory.safeSegment(context == null ? null : context.getUserId()),
                "session-" + AgentScopeRuntimeContextFactory.safeSegment(context == null ? null : context.getSessionId())
        );
    }

    public static List<String> namespaceFor(String userId, Long storyId, Object conversationId) {
        return List.of(
                "artverse",
                "user-" + AgentScopeRuntimeContextFactory.safeSegment(userId),
                "story-" + AgentScopeRuntimeContextFactory.safeSegment(storyId),
                "conversation-" + AgentScopeRuntimeContextFactory.safeSegment(conversationId)
        );
    }

    private Map<String, Object> fileValue(String content) {
        return Map.of("content", content, "encoding", "utf-8");
    }

    private String defaultAgentsMd() {
        return """
                # ArtVerse Manga Director

                你是 ArtVerse 的中文 AI 漫画创作助手。

                ## 行为约定
                - 始终使用简洁中文回答。
                - 优先保持故事、角色、服装、伏笔和章节节奏的一致性。
                - 使用工具读取或修改工作流状态，不要假装已经完成工具未执行的动作。
                - 高成本动作前要确认当前章节和用户意图。
                """;
    }
}
