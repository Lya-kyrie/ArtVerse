package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.MangaAgentMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaAgentConversationServiceTest {

    @Test
    void buildMessagesExcludesCurrentRequestHistoryAndKeepsRecentMessages() {
        Fixture fixture = fixture();
        UUID currentRequestId = UUID.randomUUID();
        UUID previousRequestId = UUID.randomUUID();
        List<MangaAgentMessage> history = List.of(
                message(fixture.user, fixture.chapter, MessageRole.USER, "old question", previousRequestId),
                message(fixture.user, fixture.chapter, MessageRole.ASSISTANT, "old answer", previousRequestId),
                message(fixture.user, fixture.chapter, MessageRole.USER, "current request echo", currentRequestId),
                message(fixture.user, fixture.chapter, MessageRole.SYSTEM, "internal", currentRequestId)
        );

        List<AgentMessage> messages = fixture.service.buildMessages(
                fixture.chapter, fixture.user, history, "current question", currentRequestId);

        assertThat(messages).extracting(AgentMessage::content)
                .anyMatch(content -> content.contains("ArtVerse Manga Director"))
                .contains("old question", "old answer", "current question")
                .doesNotContain("current request echo", "internal");
    }

    @Test
    void fallbackAfterToolSuccessWritesAssistantAndSystemMarkers() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        AgentRunToolStatus toolStatus = new AgentRunToolStatus(redisTemplate());
        when(fixture.messageRepository.findByConversationIdAndRequestIdAndRole(any(), any(UUID.class), any(MessageRole.class)))
                .thenReturn(Optional.empty());

        try (AgentRunToolStatus.RunScope scope = toolStatus.start(1L, 7L, requestId)) {
            toolStatus.recordSucceeded(
                    "save_structured_storyboard",
                    1L,
                    7L,
                    requestId,
                    25L,
                    Map.of("scenes_count", 12)
            );

            Map<String, Object> result = fixture.service.fallbackAfterToolSuccess(
                    fixture.conversation, requestId, scope.state(), "boom");

            assertThat(result.get("agent_final_response_degraded")).isEqualTo(true);
            assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                    .contains(MessageRole.ASSISTANT, MessageRole.SYSTEM);
            assertThat(fixture.saved.get(0).getContent()).contains("Storyboard rewritten and saved");
            assertThat(fixture.saved.get(1).getContent()).contains("agent encountered an error");
        }
    }

    @Test
    void resumeMessageFormatsWaitingQuestionAndSelection() {
        Fixture fixture = fixture();
        AgentUserInputRequest waiting = new AgentUserInputRequest(
                "Please choose database",
                List.of(
                        new AgentUserInputRequest.Option("mysql", "MySQL", "MySQL", true),
                        new AgentUserInputRequest.Option("postgres", "PostgreSQL", "PostgreSQL", false)
                ),
                true,
                "User decision needed"
        );

        String message = fixture.service.resumeMessage("Continue task", waiting, "PostgreSQL");

        assertThat(message).contains("Continue from the previously suspended", "Continue task", "Please choose database", "PostgreSQL");
    }

    @Test
    void deleteConversationDelegatesToRepositoryDelete() {
        Fixture fixture = fixture();

        fixture.service.deleteConversation(7L, fixture.user, fixture.conversation.getConversationUuid());

        verify(fixture.conversationRepository).delete(fixture.conversation);
    }

    private Fixture fixture() {
        MangaAgentConversationRepository conversationRepository = mock(MangaAgentConversationRepository.class);
        MangaAgentMessageRepository messageRepository = mock(MangaAgentMessageRepository.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        MangaAgentConversationService service = new MangaAgentConversationService(
                conversationRepository, messageRepository, accessService);
        User user = user(1L);
        Chapter chapter = chapter(user);
        MangaAgentConversation conversation = conversation(user, chapter);
        List<MangaAgentMessage> saved = new ArrayList<>();
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        when(conversationRepository.findByUserIdAndChapterIdAndConversationUuid(1L, 7L, conversation.getConversationUuid()))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdAndRequestIdAndRole(any(), any(UUID.class), any(MessageRole.class)))
                .thenReturn(Optional.empty());
        when(messageRepository.save(any(MangaAgentMessage.class))).thenAnswer(invocation -> {
            MangaAgentMessage savedMessage = invocation.getArgument(0);
            saved.add(savedMessage);
            return savedMessage;
        });
        return new Fixture(service, conversationRepository, messageRepository, user, chapter, conversation, saved);
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

    private MangaAgentMessage message(User user, Chapter chapter, MessageRole role, String content, UUID requestId) {
        MangaAgentMessage message = new MangaAgentMessage();
        message.setUser(user);
        message.setChapter(chapter);
        message.setRole(role);
        message.setContent(content);
        message.setRequestId(requestId);
        return message;
    }

    private record Fixture(
            MangaAgentConversationService service,
            MangaAgentConversationRepository conversationRepository,
            MangaAgentMessageRepository messageRepository,
            User user,
            Chapter chapter,
            MangaAgentConversation conversation,
            List<MangaAgentMessage> saved
    ) {
    }
}
