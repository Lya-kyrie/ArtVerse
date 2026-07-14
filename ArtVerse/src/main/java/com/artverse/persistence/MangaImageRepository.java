package com.artverse.persistence;

import com.artverse.domain.MangaImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface MangaImageRepository extends JpaRepository<MangaImage, Long> {

    List<MangaImage> findByChapterIdOrderByImageNumberAsc(Long chapterId);

    Optional<MangaImage> findByChapterIdAndImageNumber(Long chapterId, Integer imageNumber);

    void deleteByChapterId(Long chapterId);

    boolean existsByChapterId(Long chapterId);

    long countByChapterId(Long chapterId);

    @Query("SELECT m.chapter.id AS chapterId, COUNT(m) AS cnt FROM MangaImage m WHERE m.chapter.id IN :chapterIds GROUP BY m.chapter.id")
    List<Object[]> countGroupedByChapterIds(@Param("chapterIds") List<Long> chapterIds);
}
