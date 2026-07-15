package com.artverse.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class SquareDtos {
    private SquareDtos() { }

    public record StoryCard(
            Long id, String format, String title, String description,
            @JsonProperty("cover_url") String coverUrl,
            @JsonProperty("manga_style") String mangaStyle,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("chapter_count") long chapterCount,
            @JsonProperty("content_count") long contentCount) { }

    public record StoryListResponse(
            List<StoryCard> content,
            @JsonProperty("total_pages") int totalPages,
            @JsonProperty("total_elements") long totalElements,
            Map<String, Long> facets) { }

    public record ImageItem(Long id, @JsonProperty("image_number") Integer imageNumber,
                            @JsonProperty("image_url") String imageUrl) { }

    public record ChapterItem(Long id, @JsonProperty("chapter_number") Integer chapterNumber,
                              @JsonProperty("display_title") String displayTitle,
                              String content, @JsonProperty("content_count") long contentCount,
                              List<ImageItem> images) { }

    public record StoryDetail(Long id, String format, String title, String description,
                              @JsonProperty("cover_url") String coverUrl,
                              @JsonProperty("manga_style") String mangaStyle,
                              @JsonProperty("published_at") String publishedAt,
                              @JsonProperty("available_formats") List<String> availableFormats,
                              List<ChapterItem> chapters) { }
}
