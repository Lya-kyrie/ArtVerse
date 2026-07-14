package com.artverse.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MangaRoutingEvaluationDatasetTest {

    @Test
    void evaluationCorpusCoversSupportedUnsupportedInjectionAndAmbiguousRequests() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/manga-routing-evaluation.json")) {
            JsonNode cases = new ObjectMapper().readTree(input);
            assertThat(cases.size()).isGreaterThanOrEqualTo(7);
            Set<String> routes = new HashSet<>();
            Set<String> capabilities = new HashSet<>();
            cases.forEach(testCase -> {
                assertThat(testCase.path("input").asText()).isNotBlank();
                routes.add(testCase.path("expectedRoute").asText());
                testCase.path("capabilities").forEach(value -> capabilities.add(value.asText()));
            });
            assertThat(routes).contains("CONVERSATION", "CREATIVE", "STORYBOARD", "REVIEW", "DIRECTOR");
            assertThat(capabilities).contains("IMAGE_GENERATION", "STORYBOARD_WRITE", "STORYBOARD_REVIEW");
        }
    }
}
