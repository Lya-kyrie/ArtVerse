package com.artverse.application;

import com.artverse.application.workflow.MangaWorkflowOrchestrator;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.AgentConcurrencyGate;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.MangaAgentMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MangaAgentServiceTest {

    @Test
    void runDelegatesToWorkflowOrchestrator() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.orchestrator.runWithToolState(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("reply", "ok"));

        MangaAgentService.RunResult result = fixture.service.run(7L, "continue", requestId, fixture.user);

        assertThat(result.reply()).isEqualTo("ok");
    }

    @Test
    void runPropagatesWorkflowErrors() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.orchestrator.runWithToolState(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(502, "Agent service failed: model down"));

        assertThatThrownBy(() -> fixture.service.run(7L, "continue", requestId, fixture.user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Agent service failed");
    }

    @Test
    void resumeWrapsCheckedFailuresAsBusinessException() throws Exception {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        MangaAgentConversation conversation = fixture.conversation;
        MangaAgentRunService.RunSnapshot snapshot = new MangaAgentRunService.RunSnapshot(
                requestId,
                com.artverse.domain.MangaAgentRunStatus.WAITING_USER,
                "continue",
                null,
                null,
                com.artverse.application.workflow.MangaWorkflowRoute.DIRECTOR,
                new AgentUserInputRequest("Why?", List.of(), true, "Need confirmation"),
                List.of(),
                null,
                null,
                null
        );
        Mockito.doReturn(java.util.Optional.of(fixture.waitingRun))
                .when(fixture.runService).findRun(conversation, requestId);
        Mockito.doReturn(snapshot).when(fixture.runService).snapshot(fixture.waitingRun);
        Mockito.doAnswer(invocation -> {
            sneakyThrow(new IOException("disk full"));
            return null;
        }).when(fixture.orchestrator).runWithToolState(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> fixture.service.resume(7L, requestId, "answer", fixture.user))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException businessException = (BusinessException) error;
                    assertThat(businessException.getStatus()).isEqualTo(502);
                    assertThat(businessException.getMessage()).contains("disk full");
                });
    }

    @Test
    void runStreamCreatesEmitter() {
        Fixture fixture = fixture();
        assertThat(fixture.service.runAgUiStream(7L, "continue", UUID.randomUUID(), fixture.user)).isNotNull();
    }

    private Fixture fixture() {
        MangaAgentMessageRepository messageRepository = mock(MangaAgentMessageRepository.class);
        MangaAgentConversationRepository conversationRepository = mock(MangaAgentConversationRepository.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaAgentRunEventPublisher eventPublisher = mock(MangaAgentRunEventPublisher.class);
        MangaWorkflowOrchestrator orchestrator = mock(MangaWorkflowOrchestrator.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        AgentRunToolStatus toolStatus = new AgentRunToolStatus(redisTemplate());
        properties.getAgent().setRunTimeoutSeconds(5);

        User user = user(1L);
        Chapter chapter = chapter(user);
        MangaAgentConversation conversation = conversation(user, chapter);
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        when(conversationRepository.findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
                user.getId(), 7L, MangaAgentConversationStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(conversation));

        MangaAgentConversationService conversationService =
                new MangaAgentConversationService(conversationRepository, messageRepository, accessService);
        AgentConcurrencyGate concurrencyGate = mock(AgentConcurrencyGate.class);
        MangaAgentService service = new MangaAgentService(
                conversationService,
                runService,
                eventPublisher,
                orchestrator,
                toolStatus,
                accessService,
                properties,
                concurrencyGate,
                Executors.newSingleThreadExecutor()
        );
        MangaAgentRun waitingRun = new MangaAgentRun();
        waitingRun.setConversation(conversation);
        waitingRun.setUser(user);
        waitingRun.setChapter(chapter);
        waitingRun.setRequestId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        waitingRun.setStatus(com.artverse.domain.MangaAgentRunStatus.WAITING_USER);
        return new Fixture(service, orchestrator, runService, user, conversation, waitingRun);
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

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Chapter chapter(User user) {
        Story story = new Story();
        story.setId(3L);
        story.setTitle("Test Story");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setImageCount(1);
        return chapter;
    }

    private static MangaAgentConversation conversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(99L);
        conversation.setConversationUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        conversation.setUser(user);
        conversation.setChapter(chapter);
        conversation.setStory(chapter.getStory());
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private record Fixture(MangaAgentService service, MangaWorkflowOrchestrator orchestrator,
                           MangaAgentRunService runService, User user, MangaAgentConversation conversation,
                           MangaAgentRun waitingRun) {
    }
}
