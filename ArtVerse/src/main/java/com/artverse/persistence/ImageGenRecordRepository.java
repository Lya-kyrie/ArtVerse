package com.artverse.persistence;

import com.artverse.domain.ImageGenRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface ImageGenRecordRepository extends JpaRepository<ImageGenRecord, Long> {

    @Query("SELECT r FROM ImageGenRecord r WHERE r.user.id = :userId AND r.isDeleted = false ORDER BY r.createdAt DESC")
    Page<ImageGenRecord> findByUserId(Long userId, Pageable pageable);

    @Modifying
    @Query("UPDATE ImageGenRecord r SET r.status = com.artverse.domain.ImageGenStatus.FAILED, r.failureReason = :reason, r.completedAt = :now " +
            "WHERE r.user.id = :userId AND r.status = com.artverse.domain.ImageGenStatus.RUNNING AND r.createdAt < :cutoff")
    int markExpiredRunningAsFailed(Long userId, OffsetDateTime cutoff, OffsetDateTime now, String reason);
}
