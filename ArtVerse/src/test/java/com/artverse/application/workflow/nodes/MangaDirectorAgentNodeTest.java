package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgUiEventFactory;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowContextSnapshot;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentStartEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaDirectorAgentNodeTest {

    @Test
    void streamReturnsEmptyReplyWhenRunIsTerminal() {
        Fixture fixture = fixture();
        when(fixture.runService.isTerminal(fixture.requestId, fixture.user.getId(), fixture.chapter.getId())).thenReturn(true);
        when(fixture.gateway.streamEvents(any(AgentRunRequest.class))).thenReturn(Flux.just(new AgentStartEvent("session-1", "reply-1", "agent")));
        when(fixture.runService.toPayload(any())).thenReturn(Map.of("type", "workflow_step"));

        Map<String, Object> result;
        try (AgentRunToolStatus.RunScope scope = fixture.toolStatus.start(fixture.user.getId(), fixture.chapter.getId(), fixture.requestId)) {
            result = fixture.node.stream(fixture.context(scope.state()), fixture.streamContext);
        }

        assertThat(result).containsEntry("reply", "");
        verify(fixture.conversationService).saveMessage(fixture.conversation, com.artverse.domain.MessageRole.USER, fixture.message, fixture.requestId);
        verify(fixture.conversationService, never()).saveMessage(eq(fixture.conversation), eq(com.artverse.domain.MessageRole.ASSISTANT), anyString(), any());
        verify(fixture.runService).isTerminal(fixture.requestId, fixture.user.getId(), fixture.chapter.getId());
    }

    private Fixture fixture() {
        MangaAgentConversationService conversationService = mock(MangaAgentConversationService.class);
        AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
        AgentWorkspaceSyncService workspaceSyncService = mock(AgentWorkspaceSyncService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        SseEmitter sseEmitter = mock(SseEmitter.class);
        MangaAgentRunEventPublisher eventPublisher = new MangaAgentRunEventPublisher(runService, new ObjectMapper(), new AgUiEventFactory());
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setRunTimeoutSeconds(5);
        MangaDirectorAgentNode node = new MangaDirectorAgentNode(conversationService, gateway, workspaceSyncService, apiKeyService, properties, runService);

        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setTitle("Story");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setChapterNumber(1);
        chapter.setStory(story);
        chapter.setColorMode(ColorMode.BW);
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(99L);
        conversation.setUser(user);
        conversation.setChapter(chapter);
        conversation.setStory(story);
        conversation.setConversationUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);

        UUID requestId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        AgentRunToolStatus toolStatus = new AgentRunToolStatus(redisTemplate());
        MangaAgentRun run = new MangaAgentRun();
        run.setChapter(chapter);
        run.setUser(user);
        run.setConversation(conversation);
        run.setRequestId(requestId);
        run.setStatus(MangaAgentRunStatus.RUNNING);
        MangaAgentRunEventPublisher.RunEventSink sink = eventPublisher.newSink(sseEmitter);
        MangaWorkflowStreamContext streamContext = new MangaWorkflowStreamContext(run, sink);

        when(conversationService.listMessages(conversation)).thenReturn(List.of());
        when(conversationService.buildMessages(any(), any(), any(List.class), anyString(), any())).thenReturn(List.of(new AgentMessage("user", "continue")));
        when(apiKeyService.getDecryptedKey(user, "coze")).thenReturn(null);
        doNothing().when(workspaceSyncService).syncMangaDirectorKnowledge(chapter.getId(), String.valueOf(user.getId()));
        when(runService.isTerminal(requestId, user.getId(), chapter.getId())).thenReturn(true);

        return new Fixture(node, conversationService, gateway, runService, toolStatus, streamContext, conversation, user, chapter, requestId, "continue");
    }

    private RedisTemplate<String, Object> redisTemplate() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), any(), any(Duration.class));
        when(valueOperations.get(anyString())).thenReturn(null);
        return redisTemplate;
    }

    private record Fixture(
            MangaDirectorAgentNode node,
            MangaAgentConversationService conversationService,
            AgentScopeHarnessAgentGateway gateway,
            MangaAgentRunService runService,
            AgentRunToolStatus toolStatus,
            MangaWorkflowStreamContext streamContext,
            MangaAgentConversation conversation,
            User user,
            Chapter chapter,
            UUID requestId,
            String message
    ) {
        MangaWorkflowExecutionContext context(AgentRunToolStatus.RunState runState) {
            return new MangaWorkflowExecutionContext(
                    conversation,
                    message,
                    requestId,
                    "deepseek-key",
                    new com.artverse.agent.AgentModelSpec("deepseek", "", "model", "none"),
                    runState,
                    user,
                    chapter,
                    new MangaWorkflowContextSnapshot(3L, 7L, "Story", "Chapter", "style", 0, 0, "", "", "", MangaWorkflowRoute.DIRECTOR, List.of())
            );
        }
    }
}
