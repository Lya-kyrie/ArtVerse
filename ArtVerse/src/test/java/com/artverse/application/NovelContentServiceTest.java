package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.persistence.ChapterRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NovelContentServiceTest {

    @Test
    void saveCreatesImmutableRevisionAndContentChangedEvent() {
        Fixture fixture = fixture(4L, "旧正文");
        when(fixture.revisions.findLatestRevisionNumber(7L)).thenReturn(2);
        when(fixture.revisions.save(any(ChapterNovelRevision.class))).thenAnswer(invocation -> {
            ChapterNovelRevision revision = invocation.getArgument(0);
            revision.setId(30L);
            return revision;
        });
        when(fixture.chapters.saveAndFlush(fixture.chapter)).thenReturn(fixture.chapter);

        NovelContentService.SaveResult result = fixture.service.save(7L, 1L, " 新正文 ", 4L, NovelContentRevisionSource.AI);

        assertThat(result.changed()).isTrue();
        assertThat(fixture.chapter.getNovelContent()).isEqualTo("新正文");
        verify(fixture.revisions).save(argThat(revision -> revision.getRevisionNumber() == 3
                && revision.getSource() == NovelContentRevisionSource.AI
                && revision.getContent().equals("新正文")));
        verify(fixture.outbox).enqueue(eq("CHAPTER"), eq("7"), eq("CHAPTER_CONTENT_CHANGED"),
                argThat(payload -> "AI".equals(payload.get("source"))));
    }

    @Test
    void saveRejectsStaleEditorVersionBeforeWriting() {
        Fixture fixture = fixture(4L, "旧正文");

        assertThatThrownBy(() -> fixture.service.save(7L, 1L, "新正文", 3L, NovelContentRevisionSource.MANUAL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed");
        verifyNoInteractions(fixture.revisions, fixture.outbox);
    }

    @Test
    void saveRequiresBaseVersion() {
        Fixture fixture = fixture(4L, "旧正文");

        assertThatThrownBy(() -> fixture.service.save(7L, 1L, "新正文", null, NovelContentRevisionSource.MANUAL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("base_version");
        verify(fixture.chapters, never()).findByIdAndUserIdForUpdate(anyLong(), anyLong());
    }

    private Fixture fixture(Long version, String content) {
        ChapterRepository chapters = mock(ChapterRepository.class);
        ChapterNovelRevisionRepository revisions = mock(ChapterNovelRevisionRepository.class);
        ChapterAccessService access = mock(ChapterAccessService.class);
        AgentOutboxService outbox = mock(AgentOutboxService.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getImportConfig().setMaxNovelChars(50_000);
        User user = new User(); user.setId(1L);
        Story story = new Story(); story.setId(3L); story.setUser(user);
        Chapter chapter = new Chapter(); chapter.setId(7L); chapter.setStory(story); chapter.setVersion(version); chapter.setNovelContent(content);
        when(access.requireVisible(7L, 1L)).thenReturn(chapter);
        when(chapters.findByIdAndUserIdForUpdate(7L, 1L)).thenReturn(Optional.of(chapter));
        when(revisions.findByChapterIdOrderByRevisionNumberDesc(7L)).thenReturn(java.util.List.of());
        return new Fixture(new NovelContentService(chapters, revisions, access, outbox, properties), chapters, revisions, outbox, chapter);
    }

    private record Fixture(NovelContentService service, ChapterRepository chapters,
                           ChapterNovelRevisionRepository revisions, AgentOutboxService outbox, Chapter chapter) {}
}
