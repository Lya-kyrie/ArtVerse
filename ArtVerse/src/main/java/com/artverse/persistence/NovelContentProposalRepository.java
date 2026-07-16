package com.artverse.persistence;

import com.artverse.domain.NovelContentProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelContentProposalRepository extends JpaRepository<NovelContentProposal, UUID> {

    Optional<NovelContentProposal> findByIdAndChapterId(UUID id, Long chapterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM NovelContentProposal p JOIN FETCH p.chapter c JOIN FETCH c.story s JOIN FETCH s.user " +
            "WHERE p.id = :id AND c.id = :chapterId")
    Optional<NovelContentProposal> findLockedByIdAndChapterId(@Param("id") UUID id, @Param("chapterId") Long chapterId);
}
