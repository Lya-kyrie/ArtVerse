package com.artverse.api;

import com.artverse.api.dto.ChapterDto;
import com.artverse.application.NovelService;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ContentSource;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelControllerTest {

    @Test
    void importNovelDoesNotExposeLegacyImportedOriginalAsChatMessage() {
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        Story story = new Story();
        story.setId(3L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setNovelContent("正文");
        chapter.setContentSource(ContentSource.IMPORT);
        chapter.setMessages(new ArrayList<>());
        chapter.setImages(new java.util.LinkedHashSet<>());
        ChatMessage message = new ChatMessage();
        message.setId(9L);
        message.setRole(MessageRole.USER);
        message.setContent("正文");
        message.setChapter(chapter);
        chapter.getMessages().add(message);

        NovelService service = new NovelService(null, null, null, null, null, null, null) {
            @Override
            public Chapter importNovel(Long chapterId, String content) {
                assertThat(chapterId).isEqualTo(7L);
                assertThat(content).isEqualTo("正文");
                return chapter;
            }
        };
        NovelController controller = new NovelController(service, null);

        Object result = controller.importNovel(7L, java.util.Map.of("content", "正文"));

        assertThat(result).isInstanceOf(ChapterDto.class);
        ChapterDto dto = (ChapterDto) result;
        assertThat(dto.novelContent()).isEqualTo("正文");
        assertThat(dto.messages()).isEmpty();
    }

    @Test
    void chapterDtoPreservesRealConversationMessages() {
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        Story story = new Story();
        story.setId(3L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setNovelContent("正文");
        chapter.setContentSource(ContentSource.IMPORT);
        chapter.setMessages(new ArrayList<>());
        chapter.setImages(new java.util.LinkedHashSet<>());

        ChatMessage mirror = new ChatMessage();
        mirror.setId(8L);
        mirror.setRole(MessageRole.USER);
        mirror.setContent("正文");
        mirror.setChapter(chapter);
        chapter.getMessages().add(mirror);

        ChatMessage user = new ChatMessage();
        user.setId(9L);
        user.setRole(MessageRole.USER);
        user.setContent("帮我润色这一章");
        user.setChapter(chapter);
        chapter.getMessages().add(user);

        ChatMessage assistant = new ChatMessage();
        assistant.setId(10L);
        assistant.setRole(MessageRole.ASSISTANT);
        assistant.setContent("可以，先调整节奏。");
        assistant.setChapter(chapter);
        chapter.getMessages().add(assistant);

        ChapterDto dto = ChapterDto.from(chapter);

        assertThat(dto.messages()).extracting(ChapterDto.ChatMessageDto::content)
                .containsExactly("帮我润色这一章", "可以，先调整节奏。");
    }

    @Test
    void generateNovelEndpointIsRetired() {
        NovelController controller = new NovelController(null, null);

        assertThatThrownBy(() -> controller.generateNovel(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(410);
    }
}
