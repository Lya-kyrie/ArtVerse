package com.artverse.application;

import com.artverse.domain.AiConversationTitleSource;
import com.artverse.domain.AiConversationTitleState;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Isolates active-conversation writes so a uniqueness conflict cannot poison the caller's session. */
@Service
@RequiredArgsConstructor
public class MangaAgentConversationWriteService {

    private final MangaAgentConversationRepository conversationRepository;
    private final ChapterAccessService chapterAccessService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MangaAgentConversation activeOrCreate(Long chapterId, User user) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        return findActiveConversation(user.getId(), chapterId)
                .orElseGet(() -> conversationRepository.saveAndFlush(newConversation(user, chapter)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MangaAgentConversation createConversation(Long chapterId, User user) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        findActiveConversation(user.getId(), chapterId).ifPresent(existing -> {
            archiveConversation(existing);
            conversationRepository.saveAndFlush(existing);
        });
        return conversationRepository.saveAndFlush(newConversation(user, chapter));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<MangaAgentConversation> requireActiveConversation(Long chapterId, User user) {
        chapterAccessService.requireVisible(chapterId, user.getId());
        return findActiveConversation(user.getId(), chapterId);
    }

    private Optional<MangaAgentConversation> findActiveConversation(Long userId, Long chapterId) {
        return conversationRepository.findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
                userId, chapterId, AiConversationType.MANGA_AGENT, MangaAgentConversationStatus.ACTIVE);
    }

    private MangaAgentConversation newConversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setUser(user);
        conversation.setStory(chapter.getStory());
        conversation.setChapter(chapter);
        conversation.setTitle(AiConversationService.DEFAULT_TITLE);
        conversation.setConversationType(AiConversationType.MANGA_AGENT);
        conversation.setTitleSource(AiConversationTitleSource.DEFAULT);
        conversation.setTitleState(AiConversationTitleState.WAITING);
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private void archiveConversation(MangaAgentConversation conversation) {
        OffsetDateTime now = OffsetDateTime.now();
        conversation.setStatus(MangaAgentConversationStatus.ARCHIVED);
        conversation.setArchivedAt(now);
        conversation.setUpdatedAt(now);
    }
}
