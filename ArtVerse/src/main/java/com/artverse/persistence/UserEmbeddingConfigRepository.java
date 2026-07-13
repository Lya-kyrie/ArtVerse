package com.artverse.persistence;

import com.artverse.domain.UserEmbeddingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserEmbeddingConfigRepository extends JpaRepository<UserEmbeddingConfig, Long> {
    List<UserEmbeddingConfig> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<UserEmbeddingConfig> findByUserIdAndActiveTrue(Long userId);
    Optional<UserEmbeddingConfig> findByIdAndUserId(Long id, Long userId);
}
