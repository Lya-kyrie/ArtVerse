package com.artverse.persistence;

import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.AiConversationTitleSource;
import com.artverse.domain.AiConversationTitleState;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MangaAgentConversationRepository extends JpaRepository<MangaAgentConversation, Long> {

    List<MangaAgentConversation> findByUserIdAndChapterIdOrderByUpdatedAtDesc(Long userId, Long chapterId);

    Optional<MangaAgentConversation> findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
            Long userId,
            Long chapterId,
            MangaAgentConversationStatus status
    );

    Optional<MangaAgentConversation> findByUserIdAndChapterIdAndConversationUuid(
            Long userId,
            Long chapterId,
            UUID conversationUuid
    );

    Optional<MangaAgentConversation> findByUserIdAndChapterIdAndConversationUuidAndConversationType(
            Long userId,
            Long chapterId,
            UUID conversationUuid,
            AiConversationType conversationType
    );

    List<MangaAgentConversation> findByUserIdAndConversationTypeAndStatusOrderByLastActivityAtDesc(
            Long userId, AiConversationType conversationType, MangaAgentConversationStatus status);

    List<MangaAgentConversation> findByUserIdAndChapterIdAndConversationTypeOrderByLastActivityAtDesc(
            Long userId, Long chapterId, AiConversationType conversationType);

    Optional<MangaAgentConversation> findByUserIdAndConversationUuid(Long userId, UUID conversationUuid);

    Optional<MangaAgentConversation> findFirstByUserIdAndChapterIdAndConversationTypeAndStatus(
            Long userId, Long chapterId, AiConversationType conversationType, MangaAgentConversationStatus status);

    @Modifying
    @Query("UPDATE MangaAgentConversation c SET c.title = :title, c.titleSource = :source, c.titleState = :state, " +
            "c.titleGenerationStartedAt = null, c.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE c.id = :id AND c.titleSource = com.artverse.domain.AiConversationTitleSource.DEFAULT " +
            "AND c.titleState = com.artverse.domain.AiConversationTitleState.GENERATING")
    int finalizeGeneratedTitle(Long id, String title, AiConversationTitleSource source, AiConversationTitleState state);
}
