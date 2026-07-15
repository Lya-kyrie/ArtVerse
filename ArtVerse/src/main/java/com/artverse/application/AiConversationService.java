package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Shared metadata lifecycle for every persisted AI conversation. */
@Service
@RequiredArgsConstructor
public class AiConversationService {
    public static final String DEFAULT_TITLE = "新会话";
    private final MangaAgentConversationRepository repository;
    private final ChapterAccessService chapterAccessService;

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> list(User user, AiConversationType type, Long chapterId, boolean includeArchived) {
        if (type == AiConversationType.IMAGE_GEN) {
            return includeArchived
                    ? repository.findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(user.getId(), type, MangaAgentConversationStatus.ACTIVE)
                    : repository.findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(user.getId(), type, MangaAgentConversationStatus.ACTIVE);
        }
        if (chapterId == null) throw new BusinessException(400, "chapterId is required for this conversation type");
        chapterAccessService.requireVisible(chapterId, user.getId());
        return repository.findByUserIdAndChapterIdAndConversationTypeOrderByLastActivityAtDesc(user.getId(), chapterId, type)
                .stream().filter(c -> includeArchived || c.getStatus() == MangaAgentConversationStatus.ACTIVE).toList();
    }

    @Transactional
    public MangaAgentConversation create(User user, AiConversationType type, Long chapterId, String requestedTitle) {
        if (type == AiConversationType.STORY_CHAT) {
            if (chapterId == null) throw new BusinessException(400, "chapterId is required for story chat");
            return storyConversation(user, chapterId);
        }
        MangaAgentConversation c = new MangaAgentConversation();
        c.setUser(user);
        c.setConversationType(type);
        c.setStatus(MangaAgentConversationStatus.ACTIVE);
        applyInitialTitle(c, requestedTitle);
        return repository.save(c);
    }

    @Transactional
    public MangaAgentConversation storyConversation(User user, Long chapterId) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        return repository.findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
                        user.getId(), chapterId, AiConversationType.STORY_CHAT, MangaAgentConversationStatus.ACTIVE)
                .orElseGet(() -> {
                    MangaAgentConversation c = new MangaAgentConversation();
                    c.setUser(user); c.setStory(chapter.getStory()); c.setChapter(chapter);
                    c.setConversationType(AiConversationType.STORY_CHAT);
                    c.setStatus(MangaAgentConversationStatus.ACTIVE);
                    c.setTitle(DEFAULT_TITLE); c.setTitleSource(AiConversationTitleSource.DEFAULT);
                    c.setTitleState(AiConversationTitleState.WAITING);
                    return repository.save(c);
                });
    }

    @Transactional
    public MangaAgentConversation activeImageConversation(User user) {
        return repository.findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(
                        user.getId(), AiConversationType.IMAGE_GEN, MangaAgentConversationStatus.ACTIVE)
                .stream().findFirst().orElseGet(() -> create(user, AiConversationType.IMAGE_GEN, null, null));
    }

    @Transactional(readOnly = true)
    public MangaAgentConversation require(User user, UUID id) {
        return repository.findByUserIdAndConversationUuid(user.getId(), id)
                .orElseThrow(() -> new BusinessException(404, "AI conversation not found"));
    }

    @Transactional
    public MangaAgentConversation rename(User user, UUID id, String title) {
        MangaAgentConversation c = require(user, id);
        c.setTitle(normalizeManualTitle(title));
        c.setTitleSource(AiConversationTitleSource.USER);
        c.setTitleState(AiConversationTitleState.FINALIZED);
        c.setTitleGenerationStartedAt(null);
        return repository.save(c);
    }

    @Transactional
    public MangaAgentConversation archive(User user, UUID id) {
        MangaAgentConversation c = require(user, id);
        if (c.getConversationType() == AiConversationType.STORY_CHAT) {
            throw new BusinessException(409, "Story chat is managed by its chapter");
        }
        c.setStatus(MangaAgentConversationStatus.ARCHIVED);
        c.setArchivedAt(OffsetDateTime.now());
        return repository.save(c);
    }

    @Transactional
    public void autoTitle(MangaAgentConversation c, String input) {
        if (c.getTitleSource() != AiConversationTitleSource.DEFAULT || !isMeaningful(input)) return;
        c.setTitleState(AiConversationTitleState.GENERATING);
        c.setTitleGenerationStartedAt(OffsetDateTime.now());
        repository.save(c);
        // Deterministic fallback is deliberately local: primary interaction never fails or blocks on naming.
        repository.finalizeGeneratedTitle(c.getId(), fallbackTitle(input), AiConversationTitleSource.FALLBACK,
                AiConversationTitleState.FINALIZED);
    }

    @Transactional
    public void touch(MangaAgentConversation c) {
        c.setLastActivityAt(OffsetDateTime.now());
        repository.save(c);
    }

    private void applyInitialTitle(MangaAgentConversation c, String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            c.setTitle(DEFAULT_TITLE); c.setTitleSource(AiConversationTitleSource.DEFAULT); c.setTitleState(AiConversationTitleState.WAITING);
        } else {
            c.setTitle(normalizeManualTitle(requestedTitle)); c.setTitleSource(AiConversationTitleSource.USER); c.setTitleState(AiConversationTitleState.FINALIZED);
        }
    }

    private static boolean isMeaningful(String input) {
        String value = input == null ? "" : input.strip();
        return value.length() > 2 && !value.matches("(?iu)^(你好|嗨|hello|hi|谢谢|感谢|ok|好的|嗯|在吗|[\\p{So}\\p{Punct}\\s]+)$");
    }

    public static String fallbackTitle(String input) {
        String clean = input == null ? "" : input.replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .replaceAll("[`*_#>|]", "").replaceAll("\\s+", " ").strip();
        if (clean.isBlank()) return DEFAULT_TITLE;
        return clean.codePoints().limit(24).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static String normalizeManualTitle(String title) {
        String value = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").replaceAll("\\s+", " ").strip();
        if (value.isBlank()) throw new BusinessException(400, "Conversation title cannot be blank");
        if (value.codePointCount(0, value.length()) > 120) throw new BusinessException(400, "Conversation title must be at most 120 characters");
        return value;
    }
}
