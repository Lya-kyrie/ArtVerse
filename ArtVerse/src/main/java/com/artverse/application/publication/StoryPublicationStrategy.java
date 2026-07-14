package com.artverse.application.publication;

import com.artverse.domain.Story;

import java.util.Set;

public interface StoryPublicationStrategy {

    PublicationFormat format();

    void apply(Story story, boolean published, Set<Long> selectedChapterIds);
}
