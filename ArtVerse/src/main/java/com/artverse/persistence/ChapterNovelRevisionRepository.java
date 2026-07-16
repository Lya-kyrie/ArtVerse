package com.artverse.persistence;

import com.artverse.domain.ChapterNovelRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterNovelRevisionRepository extends JpaRepository<ChapterNovelRevision, Long> {

    List<ChapterNovelRevision> findByChapterIdOrderByRevisionNumberDesc(Long chapterId);

    Optional<ChapterNovelRevision> findByIdAndChapterId(Long id, Long chapterId);

    Optional<ChapterNovelRevision> findByProposalId(java.util.UUID proposalId);

    @Query("SELECT COALESCE(MAX(r.revisionNumber), 0) FROM ChapterNovelRevision r WHERE r.chapter.id = :chapterId")
    int findLatestRevisionNumber(@Param("chapterId") Long chapterId);
}
