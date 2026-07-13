package com.artverse.persistence;

import com.artverse.domain.KnowledgeIndexJob;
import com.artverse.domain.KnowledgeIndexJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface KnowledgeIndexJobRepository extends JpaRepository<KnowledgeIndexJob, Long> {
    List<KnowledgeIndexJob> findByKnowledgeUnitIdOrderByCreatedAtDesc(Long knowledgeUnitId);
    Optional<KnowledgeIndexJob> findFirstByKnowledgeUnitIdAndEmbeddingSpaceIdAndStatusOrderByCreatedAtDesc(Long knowledgeUnitId, Long embeddingSpaceId, KnowledgeIndexJobStatus status);
    @Query("SELECT j FROM KnowledgeIndexJob j WHERE j.status IN :statuses AND j.nextRunAt <= :now AND (j.status <> com.artverse.domain.KnowledgeIndexJobStatus.FAILED OR j.attempts < 5) ORDER BY j.createdAt ASC")
    List<KnowledgeIndexJob> findDispatchable(@Param("statuses") List<KnowledgeIndexJobStatus> statuses, @Param("now") OffsetDateTime now);
}
