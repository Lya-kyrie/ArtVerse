package com.artverse.persistence;

import com.artverse.domain.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {
    List<UserApiKey> findByUserId(Long userId);
    Optional<UserApiKey> findByUserIdAndSlot(Long userId, String slot);
    void deleteByUserIdAndSlot(Long userId, String slot);
}
