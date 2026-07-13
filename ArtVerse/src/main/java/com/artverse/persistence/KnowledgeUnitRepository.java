package com.artverse.persistence;

import com.artverse.domain.KnowledgeUnit;
import com.artverse.domain.KnowledgeUnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KnowledgeUnitRepository extends JpaRepository<KnowledgeUnit, Long> {
    List<KnowledgeUnit> findByStoryIdAndStatusOrderByUpdatedAtDesc(Long storyId, KnowledgeUnitStatus status);
    List<KnowledgeUnit> findByStoryIdOrderByUpdatedAtDesc(Long storyId);
    Optional<KnowledgeUnit> findByIdAndStoryId(Long id, Long storyId);
    Optional<KnowledgeUnit> findByCharacterProfileId(Long characterProfileId);
}
