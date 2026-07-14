package com.artverse.persistence;

import com.artverse.domain.MangaAgentRunArtifact;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MangaAgentRunArtifactRepository extends JpaRepository<MangaAgentRunArtifact, Long> {

    Optional<MangaAgentRunArtifact> findFirstByRunIdAndChecksumOrderByCreatedAtDesc(Long runId, String checksum);

    long countByRunIdAndArtifactType(Long runId, String artifactType);

    List<MangaAgentRunArtifact> findByRunIdOrderByCreatedAtAsc(Long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select artifact from MangaAgentRunArtifact artifact "
            + "where artifact.artifactUuid = :artifactUuid "
            + "and artifact.run.user.id = :userId and artifact.run.chapter.id = :chapterId")
    Optional<MangaAgentRunArtifact> findForCommit(
            @Param("artifactUuid") UUID artifactUuid,
            @Param("userId") Long userId,
            @Param("chapterId") Long chapterId);
}
