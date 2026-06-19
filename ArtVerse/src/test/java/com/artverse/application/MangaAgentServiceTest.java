package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaAgentMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaAgentServiceTest {

    @Test
    void runUsesGenerationGuardAndSavesAssistantReply() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.gateway.generateText(any(AgentRunRequest.class))).thenReturn(Mono.just("完成"));
        when(fixture.guard.executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("继续"),
                eq("deepseek"), eq("deepseek-chat"), any(), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(7).call());

        MangaAgentService.RunResult result = fixture.service.run(7L, "继续", requestId, fixture.user);

        assertThat(result.reply()).isEqualTo("完成");
        assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        verify(fixture.guard).executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("继续"),
                eq("deepseek"), eq("deepseek-chat"), any(), any());
    }

    @Test
    void runSavesSystemFailureMarkerWhenGatewayFails() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.gateway.generateText(any(AgentRunRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("model down")));
        when(fixture.guard.executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("继续"),
                eq("deepseek"), eq("deepseek-chat"), any(), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(7).call());

        assertThatThrownBy(() -> fixture.service.run(7L, "继续", requestId, fixture.user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Agent service failed");

        assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.SYSTEM);
        assertThat(fixture.saved.get(1).getContent()).contains("agent_run_failed", "model down");
    }

    @Test
    void runReturnsFallbackAssistantReplyWhenToolSavedBeforeGatewayFails() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.gateway.generateText(any(AgentRunRequest.class))).thenAnswer(invocation -> {
            fixture.toolStatus.recordSucceeded(
                    "save_structured_storyboard",
                    1L,
                    7L,
                    12L,
                    Map.of("saved", true, "scenes_count", 1)
            );
            return Mono.error(new IllegalStateException("final response timed out"));
        });
        when(fixture.guard.executeMangaAgentRun(eq(1L), eq(7L), eq(requestId.toString()), eq("重写分镜"),
                eq("deepseek"), eq("deepseek-chat"), any(), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(7).call());

        MangaAgentService.RunResult result = fixture.service.run(7L, "重写分镜", requestId, fixture.user);

        assertThat(result.reply()).contains("分镜已重写并保存", "最终总结回复没有及时完成");
        assertThat(fixture.saved).extracting(MangaAgentMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM);
        assertThat(fixture.saved.get(1).getContent()).contains("分镜已重写并保存");
        assertThat(fixture.saved.get(2).getContent()).contains("agent_run_degraded_after_tool_success");
    }

    @Test
    void buildMessagesExcludesCurrentRequestHistory() {
        User user = user(1L);
        Chapter chapter = chapter(user);
        UUID currentRequestId = UUID.randomUUID();
        UUID previousRequestId = UUID.randomUUID();
        List<MangaAgentMessage> history = List.of(
                message(user, chapter, MessageRole.USER, "旧问题", previousRequestId),
                message(user, chapter, MessageRole.ASSISTANT, "旧回答", previousRequestId),
                message(user, chapter, MessageRole.USER, "重复的当前问题", currentRequestId),
                message(user, chapter, MessageRole.SYSTEM, "失败内部记录", currentRequestId)
        );

        List<AgentMessage> messages = MangaAgentService.buildMessages(chapter, user, history, "当前问题", currentRequestId);

        assertThat(messages).extracting(AgentMessage::content)
                .anyMatch(content -> content.contains("ArtVerse Manga Director"))
                .contains("旧问题", "旧回答", "当前问题")
                .doesNotContain("重复的当前问题", "失败内部记录");
    }

    private Fixture fixture() {
        MangaAgentMessageRepository messageRepository = mock(MangaAgentMessageRepository.class);
        HarnessAgentGateway gateway = mock(HarnessAgentGateway.class);
        AgentWorkspaceSyncService syncService = mock(AgentWorkspaceSyncService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        GenerationGuardService guard = mock(GenerationGuardService.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        AgentRunToolStatus toolStatus = new AgentRunToolStatus();
        properties.getAgent().setRunTimeoutSeconds(5);
        properties.getDeepseek().setModel("deepseek-chat");
        Dotenv dotenv = mock(Dotenv.class);
        when(dotenv.get("DEEPSEEK_API_KEY", "")).thenReturn("");

        User user = user(1L);
        Chapter chapter = chapter(user);
        List<MangaAgentMessage> saved = new ArrayList<>();
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        when(apiKeyService.getDecryptedKey(user, "deepseek")).thenReturn("deepseek-key");
        when(apiKeyService.getDecryptedKey(user, "coze")).thenReturn("coze-key");
        when(messageRepository.findByUserIdAndRequestIdAndRole(eq(1L), any(UUID.class), any(MessageRole.class)))
                .thenReturn(Optional.empty());
        when(messageRepository.findByUserIdAndChapterIdOrderByCreatedAtAsc(1L, 7L)).thenAnswer(invocation -> List.copyOf(saved));
        when(messageRepository.save(any(MangaAgentMessage.class))).thenAnswer(invocation -> {
            MangaAgentMessage message = invocation.getArgument(0);
            saved.add(message);
            return message;
        });

        MangaAgentService service = new MangaAgentService(
                messageRepository,
                gateway,
                new AgentModelSpecFactory(properties, dotenv),
                syncService,
                apiKeyService,
                accessService,
                guard,
                properties,
                toolStatus,
                new ObjectMapper(),
                Executors.newSingleThreadExecutor()
        );
        return new Fixture(service, gateway, guard, toolStatus, user, saved);
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Chapter chapter(User user) {
        Story story = new Story();
        story.setId(3L);
        story.setTitle("故事");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setImageCount(1);
        return chapter;
    }

    private static MangaAgentMessage message(User user, Chapter chapter, MessageRole role, String content, UUID requestId) {
        MangaAgentMessage message = new MangaAgentMessage();
        message.setUser(user);
        message.setStory(chapter.getStory());
        message.setChapter(chapter);
        message.setRole(role);
        message.setContent(content);
        message.setRequestId(requestId);
        return message;
    }

    private record Fixture(MangaAgentService service,
                           HarnessAgentGateway gateway,
                           GenerationGuardService guard,
                           AgentRunToolStatus toolStatus,
                           User user,
                           List<MangaAgentMessage> saved) {
    }
}
