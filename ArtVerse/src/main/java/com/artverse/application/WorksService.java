package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.persistence.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorksService {

    private final StoryRepository storyRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMyWorks() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<Story> stories = storyRepository.findByUserIdWithChapters(userId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Story story : stories) {
            List<Map<String, Object>> chapters = new ArrayList<>();
            List<Chapter> sorted = new ArrayList<>(story.getChapters());
            sorted.sort(Comparator.comparingInt(Chapter::getChapterNumber));

            for (Chapter ch : sorted) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", ch.getId());
                cm.put("chapter_number", ch.getChapterNumber());
                cm.put("is_published", ch.getIsPublished() != null && ch.getIsPublished());
                cm.put("display_order", ch.getDisplayOrder() != null ? ch.getDisplayOrder() : ch.getChapterNumber());
                cm.put("display_title", ch.getDisplayTitle() != null ? ch.getDisplayTitle() : ("Chapter " + ch.getChapterNumber()));
                cm.put("status", ch.getStatus() != null ? ch.getStatus().name() : "DRAFT");
                chapters.add(cm);
            }

            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", story.getId());
            sm.put("title", story.getTitle());
            sm.put("description", story.getDescription() != null ? story.getDescription() : "");
            sm.put("cover_image", story.getCoverImage() != null ? story.getCoverImage() : "");
            sm.put("is_published", story.getIsPublished() != null && story.getIsPublished());
            sm.put("published_at", story.getPublishedAt() != null ? story.getPublishedAt().toString() : null);
            sm.put("created_at", story.getCreatedAt() != null ? story.getCreatedAt().toString() : null);
            sm.put("chapters", chapters);
            result.add(sm);
        }
        return result;
    }
}