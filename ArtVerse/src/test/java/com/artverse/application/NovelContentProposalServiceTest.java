package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ChatMessageCompletionStatus;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MessageRole;
import com.artverse.domain.NovelContentProposal;
import com.artverse.domain.NovelContentProposalStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.NovelContentProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NovelContentProposalServiceTest {

    @Test
    void createRejectsPartialAssistantBoundaryBeforeCallingModel() {
        Fixture fixture = fixture();
        ChatMessage partial = fixture.assistantBoundary();
        partial.setCompletionStatus(ChatMessageCompletionStatus.PARTIAL);
        when(fixture.conversations.findByUserIdAndChapterIdAndConversationUuidAndConversationType(
                1L, 7L, fixture.conversationUuid, AiConversationType.STORY_CHAT))
                .thenReturn(Optional.of(fixture.conversation));
        when(fixture.messages.findByIdAndConversationId(10L, 100L)).thenReturn(Optional.of(partial));

        assertThatThrownBy(() -> fixture.service.create(
                fixture.user, 7L, fixture.conversationUuid, 10L, 4L, fixture.llmConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("complete assistant");

        verifyNoInteractions(fixture.novelService);
        verify(fixture.proposals, never()).save(any());
    }

    @Test
    void createPersistsDraftProposalWithoutCanonicalWrite() throws Exception {
        Fixture fixture = fixture();
        ChatMessage boundary = fixture.assistantBoundary();
        List<ChatMessage> boundedMessages = List.of(fixture.userMessage(), boundary);
        when(fixture.conversations.findByUserIdAndChapterIdAndConversationUuidAndConversationType(
                1L, 7L, fixture.conversationUuid, AiConversationType.STORY_CHAT))
                .thenReturn(Optional.of(fixture.conversation));
        when(fixture.messages.findByIdAndConversationId(10L, 100L)).thenReturn(Optional.of(boundary));
        when(fixture.messages.findByConversationIdAndIdLessThanEqualOrderByCreatedAtAscIdAsc(100L, 10L))
                .thenReturn(boundedMessages);
        when(fixture.novelService.generateNovelSnapshot(1L, 3L, 7L, "old novel", boundedMessages, fixture.llmConfig))
                .thenReturn(new NovelService.GeneratedNovelSnapshot(
                        " new novel ",
                        fixture.skillRegistry.selectionForNovelMode(NovelBusinessSkillMode.CHAPTER_WRITING)));
        when(fixture.proposals.save(any(NovelContentProposal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NovelContentProposalService.ProposalResult result = fixture.service.create(
                fixture.user, 7L, fixture.conversationUuid, 10L, 4L, fixture.llmConfig);

        assertThat(result.content()).isEqualTo("new novel");
        assertThat(result.baseVersion()).isEqualTo(4L);
        assertThat(result.throughMessageId()).isEqualTo(10L);
        verify(fixture.canonical, never()).save(anyLong(), anyLong(), anyString(), anyLong(), any(), any());
    }

    @Test
    void createPersistsSkillVersionsFromSnapshotSelection() throws Exception {
        Fixture fixture = fixture();
        ChatMessage boundary = fixture.assistantBoundary();
        List<ChatMessage> boundedMessages = List.of(fixture.userMessage(), boundary);
        when(fixture.conversations.findByUserIdAndChapterIdAndConversationUuidAndConversationType(
                1L, 7L, fixture.conversationUuid, AiConversationType.STORY_CHAT))
                .thenReturn(Optional.of(fixture.conversation));
        when(fixture.messages.findByIdAndConversationId(10L, 100L)).thenReturn(Optional.of(boundary));
        when(fixture.messages.findByConversationIdAndIdLessThanEqualOrderByCreatedAtAscIdAsc(100L, 10L))
                .thenReturn(boundedMessages);
        when(fixture.novelService.generateNovelSnapshot(1L, 3L, 7L, "old novel", boundedMessages, fixture.llmConfig))
                .thenReturn(new NovelService.GeneratedNovelSnapshot(
                        "new novel",
                        fixture.skillRegistry.selectionForNovelMode(NovelBusinessSkillMode.CHAPTER_WRITING)));
        when(fixture.proposals.save(any(NovelContentProposal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.create(fixture.user, 7L, fixture.conversationUuid, 10L, 4L, fixture.llmConfig);

        verify(fixture.proposals).save(any(NovelContentProposal.class));
    }

    @Test
    void commitRejectsEditedHashConflictBeforeCanonicalWrite() {
        Fixture fixture = fixture();
        NovelContentProposal proposal = fixture.proposal();
        when(fixture.proposals.findLockedByIdAndChapterId(proposal.getId(), 7L)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> fixture.service.commit(fixture.user, 7L, proposal.getId(), 4L, "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed");

        verifyNoInteractions(fixture.canonical);
    }

    private Fixture fixture() {
        NovelContentProposalRepository proposals = mock(NovelContentProposalRepository.class);
        MangaAgentConversationRepository conversations = mock(MangaAgentConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChapterNovelRevisionRepository revisions = mock(ChapterNovelRevisionRepository.class);
        NovelContentService canonical = mock(NovelContentService.class);
        NovelService novelService = mock(NovelService.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getImportConfig().setMaxNovelChars(50_000);
        ArtVerseSkillRegistry skillRegistry = new ArtVerseSkillRegistry(
                new org.springframework.core.io.DefaultResourceLoader(), properties);
        skillRegistry.initialize();
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setVersion(4L);
        chapter.setNovelContent("old novel");
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(100L);
        conversation.setConversationUuid(UUID.randomUUID());
        conversation.setUser(user);
        conversation.setStory(story);
        conversation.setChapter(chapter);
        conversation.setConversationType(AiConversationType.STORY_CHAT);
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        UserProviderConfig llm = new UserProviderConfig(
                ApiKeyService.SLOT_LLM, "deepseek", "DeepSeek", "sk",
                "https://api.example.com/v1", "model-a", 22L);
        NovelContentProposalService service = new NovelContentProposalService(
                proposals, conversations, messages, revisions, canonical, novelService, properties, new ObjectMapper());
        return new Fixture(service, proposals, conversations, messages, revisions, canonical, novelService,
                user, story, chapter, conversation, conversation.getConversationUuid(), llm, skillRegistry);
    }

    private record Fixture(NovelContentProposalService service,
                           NovelContentProposalRepository proposals,
                           MangaAgentConversationRepository conversations,
                           ChatMessageRepository messages,
                           ChapterNovelRevisionRepository revisions,
                           NovelContentService canonical,
                           NovelService novelService,
                           User user,
                           Story story,
                           Chapter chapter,
                           MangaAgentConversation conversation,
                           UUID conversationUuid,
                           UserProviderConfig llmConfig,
                           ArtVerseSkillRegistry skillRegistry) {
        ChatMessage userMessage() {
            ChatMessage message = new ChatMessage();
            message.setId(9L);
            message.setConversation(conversation);
            message.setChapter(chapter);
            message.setRole(MessageRole.USER);
            message.setContent("help me rewrite");
            message.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
            return message;
        }

        ChatMessage assistantBoundary() {
            ChatMessage message = new ChatMessage();
            message.setId(10L);
            message.setConversation(conversation);
            message.setChapter(chapter);
            message.setRole(MessageRole.ASSISTANT);
            message.setContent("ok");
            message.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
            return message;
        }

        NovelContentProposal proposal() {
            NovelContentProposal proposal = new NovelContentProposal();
            proposal.setId(UUID.randomUUID());
            proposal.setChapter(chapter);
            proposal.setConversation(conversation);
            proposal.setThroughMessage(assistantBoundary());
            proposal.setBaseVersion(4L);
            proposal.setDraftContent("candidate novel");
            proposal.setDraftContentHash(ToolIdempotencyService.sha256("candidate novel"));
            proposal.setGeneratedContent("candidate novel");
            proposal.setGeneratedContentHash(proposal.getDraftContentHash());
            proposal.setStatus(NovelContentProposalStatus.DRAFT);
            return proposal;
        }
    }
}
