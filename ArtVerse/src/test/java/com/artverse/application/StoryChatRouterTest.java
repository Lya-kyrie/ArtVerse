package com.artverse.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryChatRouterTest {

    private final StoryChatRouter router = new StoryChatRouter();

    @Test
    void explicitSaveRequestRoutesToWrite() {
        assertThat(router.classify("请续写这一段并保存到小说原文"))
                .isEqualTo(StoryChatRoute.WRITE);
    }

    @Test
    void reviewAndPlainDraftStayReadOnly() {
        assertThat(router.classify("帮我审阅这一章的问题"))
                .isEqualTo(StoryChatRoute.REVIEW);
        assertThat(router.classify("继续写一段给我看看"))
                .isEqualTo(StoryChatRoute.DRAFT);
    }

    @Test
    void discussionRoutesToRead() {
        assertThat(router.classify("你觉得这个角色动机合理吗"))
                .isEqualTo(StoryChatRoute.READ);
    }
}
