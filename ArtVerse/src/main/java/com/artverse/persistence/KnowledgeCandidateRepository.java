package com.artverse.persistence;

import com.artverse.domain.KnowledgeCandidate;
import com.artverse.domain.KnowledgeCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeCandidateRepository extends JpaRepository<KnowledgeCandidate, Long> {

    List<KnowledgeCandidate> findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
            Long storyId, Long userId, KnowledgeCandidateStatus status);

    List<KnowledgeCandidate> findByStoryIdAndUserIdOrderByCreatedAtDesc(Long storyId, Long userId);

    Optional<KnowledgeCandidate> findByIdAndStoryIdAndUserId(Long id, Long storyId, Long userId);

    Optional<KnowledgeCandidate> findFirstByStoryIdAndProposedHashAndStatus(
            Long storyId, String proposedHash, KnowledgeCandidateStatus status);
}
