package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.ApiKeyService;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaChatNodeTest {

    @Test
    void delegatesToLlmGateway() {
        var fixture = fixture("你好");
        when(fixture.gateway.generateText(any()))
                .thenReturn(Mono.just("你好！我是漫画助手，有什么可以帮你？"));

        Map<String, Object> result = fixture.node.run(fixture.context);

        assertThat(result.get("reply")).isEqualTo("你好！我是漫画助手，有什么可以帮你？");
        verify(fixture.conversationService).saveMessage(
                fixture.context.conversation(),
                MessageRole.USER,
                "你好",
                fixture.context.requestId()
        );
        verify(fixture.conversationService).saveMessage(
                fixture.context.conversation(),
                MessageRole.ASSISTANT,
                "你好！我是漫画助手，有什么可以帮你？",
                fixture.context.requestId()
        );
    }

    @Test
    void routeReturnsChat() {
        var fixture = fixture("测试");
        assertThat(fixture.node.route()).isEqualTo(MangaWorkflowRoute.CHAT);
    }

    @Test
    void agentTaskTypeReturnsMangaChat() {
        var fixture = fixture("测试");
        assertThat(fixture.node.agentTaskType()).isEqualTo(AgentTaskType.MANGA_CHAT);
    }

    @Test
    void savesFailureMessageOnError() {
        var fixture = fixture("触发错误");
        when(fixture.gateway.generateText(any()))
                .thenReturn(Mono.error(new RuntimeException("model down")));

        try {
            fixture.node.run(fixture.context);
        } catch (Exception ignored) {
            // expected
        }

        verify(fixture.conversationService).saveFailureMessage(
                fixture.context.conversation(), "model down", fixture.context.requestId());
    }

    @Test
    void syncsWorkspaceOnRun() {
        var fixture = fixture("进度");
        when(fixture.gateway.generateText(any()))
                .thenReturn(Mono.just("当前进度..."));

        fixture.node.run(fixture.context);

        verify(fixture.syncService).syncMangaDirectorKnowledge(3L, "1");
    }

    @Test
    void buildsMessagesWithHistory() {
        var fixture = fixture("继续对话");
        when(fixture.gateway.generateText(any()))
                .thenReturn(Mono.just("好的"));
        when(fixture.conversationService.buildMessages(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AgentMessage("user", "继续对话")));

        fixture.node.run(fixture.context);

        verify(fixture.conversationService).buildMessages(
                eq(fixture.context.chapter()),
                eq(fixture.context.user()),
                any(),
                eq("继续对话"),
                eq(fixture.context.requestId())
        );
    }

    private Fixture fixture(String message) {
        MangaAgentConversationService conversationService = mock(MangaAgentConversationService.class);
        AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
        AgentWorkspaceSyncService syncService = mock(AgentWorkspaceSyncService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setRunTimeoutSeconds(5);

        when(apiKeyService.getDecryptedKey(any(), eq("coze"))).thenReturn("coze-key");

        MangaChatNode node = new MangaChatNode(conversationService, gateway, syncService,
                apiKeyService, properties, runService);

        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(2L);
        story.setTitle("故事");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(3L);
        chapter.setChapterNumber(1);
        chapter.setStory(story);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(4L);
        conversation.setConversationUuid(UUID.randomUUID());
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        UUID requestId = UUID.randomUUID();
        MangaWorkflowContextSnapshot snapshot = new MangaWorkflowContextSnapshot(
                story.getId(), chapter.getId(), story.getTitle(), "第1话",
                "黑白漫画", 0, 0, "源文", "", "user: " + message,
                MangaWorkflowRoute.CHAT, List.of()
        );
        MangaWorkflowExecutionContext context = new MangaWorkflowExecutionContext(
                conversation, message, requestId, "deepseek-key",
                new AgentModelSpec("deepseek", "https://api.deepseek.com", "deepseek-chat", "hash"),
                mock(AgentRunToolStatus.RunState.class), user, chapter, snapshot
        );
        return new Fixture(node, gateway, conversationService, syncService, context);
    }

    private record Fixture(
            MangaChatNode node,
            AgentScopeHarnessAgentGateway gateway,
            MangaAgentConversationService conversationService,
            AgentWorkspaceSyncService syncService,
            MangaWorkflowExecutionContext context) {
    }
}
