package com.artverse.application.publication;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationStrategyRegistryTest {

    @Test
    void failsFastWhenAFormatHasNoStrategy() {
        assertThatThrownBy(() -> new PublicationStrategyRegistry(List.of(new MangaPublicationStrategy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing publication strategy for NOVEL");
    }

    @Test
    void failsFastWhenAFormatHasDuplicateStrategies() {
        assertThatThrownBy(() -> new PublicationStrategyRegistry(List.of(
                new MangaPublicationStrategy(),
                new MangaPublicationStrategy(),
                new NovelPublicationStrategy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate publication strategy for MANGA");
    }
}
