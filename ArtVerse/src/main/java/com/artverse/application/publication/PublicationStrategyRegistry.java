package com.artverse.application.publication;

import com.artverse.domain.Story;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PublicationStrategyRegistry {

    private final Map<PublicationFormat, StoryPublicationStrategy> strategies;

    public PublicationStrategyRegistry(List<StoryPublicationStrategy> strategies) {
        EnumMap<PublicationFormat, StoryPublicationStrategy> indexed = new EnumMap<>(PublicationFormat.class);
        for (StoryPublicationStrategy strategy : strategies) {
            StoryPublicationStrategy duplicate = indexed.put(strategy.format(), strategy);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate publication strategy for " + strategy.format());
            }
        }
        for (PublicationFormat format : PublicationFormat.values()) {
            if (!indexed.containsKey(format)) {
                throw new IllegalStateException("Missing publication strategy for " + format);
            }
        }
        this.strategies = Map.copyOf(indexed);
    }

    public void apply(PublicationFormat format, Story story, boolean published, Set<Long> selectedChapterIds) {
        strategies.get(format).apply(story, published, selectedChapterIds);
    }
}
