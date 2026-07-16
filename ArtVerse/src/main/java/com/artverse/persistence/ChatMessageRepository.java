package com.artverse.persistence;

import com.artverse.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByChapterIdOrderByCreatedAtAsc(Long chapterId);

    List<ChatMessage> findByConversationIdAndIdLessThanEqualOrderByCreatedAtAscIdAsc(Long conversationId, Long id);

    Optional<ChatMessage> findByIdAndConversationId(Long id, Long conversationId);

    void deleteByChapterId(Long chapterId);

    boolean existsByChapterId(Long chapterId);
}
