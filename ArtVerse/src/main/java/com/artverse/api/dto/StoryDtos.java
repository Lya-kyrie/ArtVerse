package com.artverse.api.dto;

import com.artverse.application.publication.PublicationFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class StoryDtos {

    private StoryDtos() {
    }

    public record PublishRequest(
            @JsonProperty("is_published") Boolean isPublished,
            @JsonProperty("format") String format,
            @JsonProperty("chapter_ids") List<Long> chapterIds) {

        public boolean published() {
            return Boolean.TRUE.equals(isPublished);
        }

        public PublicationFormat publicationFormat() {
            return PublicationFormat.fromApiValue(format);
        }
    }
}
