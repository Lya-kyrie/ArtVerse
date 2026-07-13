package com.artverse.persistence;

import com.artverse.domain.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {
    List<UserApiKey> findByUserId(Long userId);
    List<UserApiKey> findByUserIdAndSlotOrderByCreatedAtAsc(Long userId, String slot);
    Optional<UserApiKey> findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(Long userId, String slot);
    Optional<UserApiKey> findByIdAndUserId(Long id, Long userId);
    void deleteByUserIdAndSlot(Long userId, String slot);
    long deleteByIdAndUserId(Long id, Long userId);
}
