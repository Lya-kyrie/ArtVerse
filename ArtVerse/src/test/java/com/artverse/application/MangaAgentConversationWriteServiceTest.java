package com.artverse.application;

import com.artverse.domain.AiConversationType;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentConversationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaAgentConversationWriteServiceTest {

    @Test
    void createConversationArchivesOnlyTheActiveMangaConversationBeforeReplacement() {
        Fixture fixture = fixture();
        MangaAgentConversation replacement = conversation(fixture.user, fixture.chapter);
        replacement.setId(100L);
        replacement.setConversationUuid(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        when(fixture.repository.findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
                1L, 7L, AiConversationType.MANGA_AGENT, MangaAgentConversationStatus.ACTIVE))
                .thenReturn(Optional.of(fixture.activeConversation));
        when(fixture.repository.saveAndFlush(any(MangaAgentConversation.class)))
                .thenReturn(fixture.activeConversation)
                .thenReturn(replacement);

        MangaAgentConversation result = fixture.service.createConversation(7L, fixture.user);

        assertThat(result).isEqualTo(replacement);
        assertThat(fixture.activeConversation.getStatus()).isEqualTo(MangaAgentConversationStatus.ARCHIVED);
        var inOrder = inOrder(fixture.repository);
        inOrder.verify(fixture.repository).saveAndFlush(fixture.activeConversation);
        inOrder.verify(fixture.repository).saveAndFlush(any(MangaAgentConversation.class));
    }

    @Test
    void activeOrCreateLooksUpOnlyMangaAgentConversations() {
        Fixture fixture = fixture();
        when(fixture.repository.findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
                1L, 7L, AiConversationType.MANGA_AGENT, MangaAgentConversationStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(fixture.repository.saveAndFlush(any(MangaAgentConversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MangaAgentConversation result = fixture.service.activeOrCreate(7L, fixture.user);

        assertThat(result.getConversationType()).isEqualTo(AiConversationType.MANGA_AGENT);
        verify(fixture.repository).findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
                1L, 7L, AiConversationType.MANGA_AGENT, MangaAgentConversationStatus.ACTIVE);
    }

    private Fixture fixture() {
        MangaAgentConversationRepository repository = mock(MangaAgentConversationRepository.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setColorMode(ColorMode.BW);
        chapter.setImageCount(1);
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        return new Fixture(new MangaAgentConversationWriteService(repository, accessService), repository, user, chapter,
                conversation(user, chapter));
    }

    private MangaAgentConversation conversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(99L);
        conversation.setConversationUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        conversation.setUser(user);
        conversation.setStory(chapter.getStory());
        conversation.setChapter(chapter);
        conversation.setConversationType(AiConversationType.MANGA_AGENT);
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private record Fixture(MangaAgentConversationWriteService service,
                           MangaAgentConversationRepository repository,
                           User user,
                           Chapter chapter,
                           MangaAgentConversation activeConversation) {
    }
}
