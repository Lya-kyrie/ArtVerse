package com.artverse.persistence;

import com.artverse.domain.EmbeddingSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface EmbeddingSpaceRepository extends JpaRepository<EmbeddingSpace, Long> {
    Optional<EmbeddingSpace> findByConfigIdAndConfigVersion(Long configId, Integer configVersion);
    @Query(value = "SELECT es.* FROM embedding_spaces es JOIN story_embedding_spaces ses ON ses.embedding_space_id = es.id WHERE ses.story_id = :storyId", nativeQuery = true)
    Optional<EmbeddingSpace> findActiveByStoryId(@Param("storyId") Long storyId);
}
