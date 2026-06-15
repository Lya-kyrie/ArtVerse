package com.artverse.persistence;

import com.artverse.domain.CharacterProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterProfileRepository extends JpaRepository<CharacterProfile, Long> {

    List<CharacterProfile> findByStoryIdOrderByIdAsc(Long storyId);

    void deleteByStoryIdAndId(Long storyId, Long id);
}