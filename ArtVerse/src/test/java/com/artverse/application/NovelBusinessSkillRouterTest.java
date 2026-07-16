package com.artverse.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NovelBusinessSkillRouterTest {

    private final NovelBusinessSkillRouter router = new NovelBusinessSkillRouter();

    @Test
    void classifiesReviewRequests() {
        assertThat(router.classify("帮我审一下这段剧情").mode())
                .isEqualTo(NovelBusinessSkillMode.REVIEW);
    }

    @Test
    void classifiesPolishRequestsBeforeGenericWriting() {
        assertThat(router.classify("把这段正文润色一下，文风更顺一些").mode())
                .isEqualTo(NovelBusinessSkillMode.PROSE_POLISH);
    }

    @Test
    void classifiesDraftRequests() {
        assertThat(router.classify("把这段设定写成正文场景").mode())
                .isEqualTo(NovelBusinessSkillMode.CHAPTER_WRITING);
    }

    @Test
    void classifiesIdeationRequests() {
        assertThat(router.classify("帮我想一下这个人物后续剧情走向").mode())
                .isEqualTo(NovelBusinessSkillMode.IDEATION);
    }

    @Test
    void fallsBackToNoneWhenNoSignalMatches() {
        assertThat(router.classify("你好").mode())
                .isEqualTo(NovelBusinessSkillMode.NONE);
    }
}
