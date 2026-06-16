package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import com.artverse.persistence.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SquareService {

    private final StoryRepository storyRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> listPublishedStories(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        Page<Story> result;
        if (search != null && !search.isBlank()) {
            result = storyRepository.searchPublishedStories(search.trim(), pageable);
        } else {
            result = storyRepository.findPublishedStories(pageable);
        }

        List<Map<String, Object>> content = result.getContent().stream()
                .map(story -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", story.getId());
                    m.put("title", story.getTitle());
                    m.put("description", story.getDescription() != null ? story.getDescription() : "");
                    m.put("cover_url", story.getCoverImage() != null ? story.getCoverImage() : "");
                    m.put("manga_style", story.getMangaStyle() != null ? story.getMangaStyle() : "");
                    m.put("published_at", story.getPublishedAt() != null ? story.getPublishedAt().toString() : null);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("total_pages", result.getTotalPages());
        response.put("total_elements", result.getTotalElements());
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPublishedStoryDetail(Long id) {
        Story story = storyRepository.findPublishedById(id)
                .orElseThrow(() -> new BusinessException(404, "Story not found or not published"));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", story.getId());
        detail.put("title", story.getTitle());
        detail.put("description", story.getDescription() != null ? story.getDescription() : "");
        detail.put("cover_url", story.getCoverImage() != null ? story.getCoverImage() : "");
        detail.put("manga_style", story.getMangaStyle() != null ? story.getMangaStyle() : "");
        detail.put("published_at", story.getPublishedAt() != null ? story.getPublishedAt().toString() : null);

        // Published chapters with images
        List<Map<String, Object>> chapters = story.getChapters().stream()
                .filter(ch -> ch.getIsPublished() != null && ch.getIsPublished())
                .sorted(Comparator.comparingInt(ch -> ch.getDisplayOrder() != null ? ch.getDisplayOrder() : ch.getChapterNumber()))
                .map(ch -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("id", ch.getId());
                    cm.put("chapter_number", ch.getChapterNumber());
                    cm.put("display_title", ch.getDisplayTitle() != null ? ch.getDisplayTitle() : ("第" + ch.getChapterNumber() + "话"));
                    // Images sorted by image_number
                    List<Map<String, Object>> images = ch.getImages().stream()
                            .sorted(Comparator.comparingInt(MangaImage::getImageNumber))
                            .map(img -> {
                                Map<String, Object> im = new LinkedHashMap<>();
                                im.put("id", img.getId());
                                im.put("image_number", img.getImageNumber());
                                im.put("image_url", img.getImagePath());
                                return im;
                            })
                            .collect(Collectors.toList());
                    cm.put("images", images);
                    return cm;
                })
                .collect(Collectors.toList());

        detail.put("chapters", chapters);
        return detail;
    }
}
