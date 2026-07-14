package com.artverse.api.dto;

import com.artverse.application.publication.PublicationFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryDtosTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishRequestKeepsExistingSnakeCaseContract() throws Exception {
        StoryDtos.PublishRequest request = objectMapper.readValue(
                """
                        {
                          "is_published": true,
                          "format": "novel",
                          "chapter_ids": [11, 12]
                        }
                        """,
                StoryDtos.PublishRequest.class);

        assertThat(request.published()).isTrue();
        assertThat(request.publicationFormat()).isEqualTo(PublicationFormat.NOVEL);
        assertThat(request.chapterIds()).isEqualTo(List.of(11L, 12L));
    }

    @Test
    void missingFormatDefaultsToManga() throws Exception {
        StoryDtos.PublishRequest request = objectMapper.readValue(
                "{\"is_published\": false}",
                StoryDtos.PublishRequest.class);

        assertThat(request.publicationFormat()).isEqualTo(PublicationFormat.MANGA);
        assertThat(request.published()).isFalse();
        assertThat(request.chapterIds()).isNull();
    }
}
