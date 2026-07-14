package com.artverse.application.publication;

import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;

@Component
public class NovelPublicationStrategy implements StoryPublicationStrategy {

    @Override
    public PublicationFormat format() {
        return PublicationFormat.NOVEL;
    }

    @Override
    public void apply(Story story, boolean published, Set<Long> selectedChapterIds) {
        story.setNovelIsPublished(published);
        story.setNovelPublishedAt(published ? OffsetDateTime.now() : null);

        boolean publishAll = selectedChapterIds.isEmpty();
        for (Chapter chapter : story.getChapters()) {
            boolean chapterPublished = published && (publishAll || selectedChapterIds.contains(chapter.getId()));
            chapter.setNovelIsPublished(chapterPublished);
        }
    }
}
