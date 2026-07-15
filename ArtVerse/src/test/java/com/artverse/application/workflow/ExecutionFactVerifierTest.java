package com.artverse.application.workflow;

import com.artverse.application.StoryboardArtifactService;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionFactVerifierTest {

    @Test
    void verifiesCommittedStoryboardAgainstPersistedArtifactFacts() {
        StoryboardArtifactService artifacts = mock(StoryboardArtifactService.class);
        ExecutionFactVerifier verifier = new ExecutionFactVerifier(artifacts);
        MangaAgentConversation conversation = conversation(10L, 20L, 2);
        UUID requestId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        when(artifacts.list(10L, 20L, requestId)).thenReturn(List.of(
                new StoryboardArtifactService.ArtifactView(artifactId, requestId, "STORYBOARD_DRAFT", "COMMITTED",
                        "v1", Map.of("scenes", List.of(Map.of("index", 1), Map.of("index", 2))), Map.of(), "sha256")));

        ExecutionFactVerifier.VerifiedFacts result = verifier.verify(
                conversation, requestId, MangaWorkflowRoute.STORYBOARD, MangaWorkflowResult.success("已提交分镜"));

        assertThat(result.resultSchema()).isEqualTo("storyboard.outcome.v1");
        assertThat(result.facts()).containsEntry("committed", true)
                .containsEntry("artifact_id", artifactId.toString())
                .containsEntry("scene_count", 2)
                .containsEntry("artifact_checksum", "sha256");
    }

    @Test
    void rejectsStoryboardReplyWhenPersistedScenesDoNotMatchChapter() {
        StoryboardArtifactService artifacts = mock(StoryboardArtifactService.class);
        ExecutionFactVerifier verifier = new ExecutionFactVerifier(artifacts);
        MangaAgentConversation conversation = conversation(10L, 20L, 2);
        UUID requestId = UUID.randomUUID();
        when(artifacts.list(10L, 20L, requestId)).thenReturn(List.of(
                new StoryboardArtifactService.ArtifactView(UUID.randomUUID(), requestId, "STORYBOARD_DRAFT", "COMMITTED",
                        "v1", Map.of("scenes", List.of(Map.of("index", 1))), Map.of(), "sha256")));

        assertThatThrownBy(() -> verifier.verify(
                conversation, requestId, MangaWorkflowRoute.STORYBOARD, MangaWorkflowResult.success("已提交分镜")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scene count");
    }

    private MangaAgentConversation conversation(Long userId, Long chapterId, int imageCount) {
        User user = mock(User.class);
        Chapter chapter = mock(Chapter.class);
        when(user.getId()).thenReturn(userId);
        when(chapter.getId()).thenReturn(chapterId);
        when(chapter.getImageCount()).thenReturn(imageCount);
        MangaAgentConversation conversation = mock(MangaAgentConversation.class);
        when(conversation.getUser()).thenReturn(user);
        when(conversation.getChapter()).thenReturn(chapter);
        return conversation;
    }
}
