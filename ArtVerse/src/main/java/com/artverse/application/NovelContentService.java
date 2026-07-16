package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.persistence.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns the canonical chapter text and immutable history; all writers delegate here. */
@Service
@RequiredArgsConstructor
public class NovelContentService {
    private final ChapterRepository chapterRepository;
    private final ChapterNovelRevisionRepository revisionRepository;
    private final ChapterAccessService chapterAccessService;
    private final AgentOutboxService outboxService;
    private final ArtVerseProperties properties;

    @Transactional(readOnly = true)
    public List<ChapterNovelRevision> listRevisions(Long chapterId, Long userId) {
        chapterAccessService.requireVisible(chapterId, userId);
        return revisionRepository.findByChapterIdOrderByRevisionNumberDesc(chapterId);
    }

    @Transactional
    public SaveResult save(Long chapterId, Long userId, String content, Long baseVersion,
                           NovelContentRevisionSource source) {
        return save(chapterId, userId, content, baseVersion, source, null);
    }

    @Transactional
    public SaveResult save(Long chapterId, Long userId, String content, Long baseVersion,
                           NovelContentRevisionSource source, NovelContentProposal proposal) {
        String normalized = normalize(content);
        if (baseVersion == null) {
            throw new BusinessException(400, "base_version is required");
        }
        Chapter chapter = chapterRepository.findByIdAndUserIdForUpdate(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        if (!baseVersion.equals(chapter.getVersion())) {
            throw new BusinessException(409, "The novel text changed. Refresh before saving your version.");
        }
        String hash = ToolIdempotencyService.sha256(normalized);
        if (hash.equals(ToolIdempotencyService.sha256(emptyToBlank(chapter.getNovelContent())))) {
            return new SaveResult(chapter, latestRevision(chapterId), false);
        }

        ChapterNovelRevision revision = new ChapterNovelRevision();
        revision.setChapter(chapter);
        revision.setRevisionNumber(revisionRepository.findLatestRevisionNumber(chapterId) + 1);
        revision.setContent(normalized);
        revision.setContentHash(hash);
        revision.setSource(source == null ? NovelContentRevisionSource.MANUAL : source);
        revision.setProposal(proposal);
        revision.setCreatedBy(chapter.getStory().getUser());
        revision = revisionRepository.save(revision);

        chapter.setNovelContent(normalized);
        Chapter saved = chapterRepository.saveAndFlush(chapter);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        payload.put("story_id", saved.getStory().getId());
        payload.put("chapter_id", chapterId);
        if (saved.getChapterNumber() != null) payload.put("chapter_number", saved.getChapterNumber());
        payload.put("source", revision.getSource().name());
        outboxService.enqueue("CHAPTER", String.valueOf(chapterId), "CHAPTER_CONTENT_CHANGED", payload);
        return new SaveResult(saved, revision, true);
    }

    @Transactional
    public SaveResult restore(Long chapterId, Long revisionId, Long userId, Long baseVersion) {
        ChapterNovelRevision revision = revisionRepository.findByIdAndChapterId(revisionId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "Novel revision not found"));
        return save(chapterId, userId, revision.getContent(), baseVersion, NovelContentRevisionSource.RESTORE);
    }

    private ChapterNovelRevision latestRevision(Long chapterId) {
        return revisionRepository.findByChapterIdOrderByRevisionNumberDesc(chapterId).stream().findFirst().orElse(null);
    }

    private String normalize(String content) {
        if (content == null || content.isBlank()) throw new BusinessException(400, "Novel content cannot be empty");
        String value = content.trim();
        if (value.length() > properties.getImportConfig().getMaxNovelChars()) {
            throw new BusinessException(400, "Novel content exceeds max length of " + properties.getImportConfig().getMaxNovelChars());
        }
        return value;
    }

    private String emptyToBlank(String value) { return value == null ? "" : value.trim(); }

    public record SaveResult(Chapter chapter, ChapterNovelRevision revision, boolean changed) {}
}
