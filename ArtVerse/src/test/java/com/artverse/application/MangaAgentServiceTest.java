package com.artverse.application;

import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.application.workflow.MangaWorkflowOrchestrator;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.MangaAgentMessageRepository;
import com.artverse.persistence.MangaImageRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MangaAgentServiceTest {

    @Test
    void runDelegatesToWorkflowOrchestrator() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.orchestrator.runWithToolState(any(), any(), any(), any()))
                .thenReturn(Map.of("reply", "完成"));

        MangaAgentService.RunResult result = fixture.service.run(7L, "continue", requestId, fixture.user);

        assertThat(result.reply()).isEqualTo("完成");
    }

    @Test
    void runPropagatesWorkflowErrors() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        when(fixture.orchestrator.runWithToolState(any(), any(), any(), any()))
                .thenThrow(new BusinessException(502, "Agent service failed: model down"));

        assertThatThrownBy(() -> fixture.service.run(7L, "continue", requestId, fixture.user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Agent service failed");
    }

    @Test
    void runStreamCreatesEmitter() {
        Fixture fixture = fixture();
        assertThat(fixture.service.runStream(7L, "continue", UUID.randomUUID(), fixture.user)).isNotNull();
    }

    private Fixture fixture() {
        MangaAgentMessageRepository messageRepository = mock(MangaAgentMessageRepository.class);
        HarnessAgentGateway gateway = mock(HarnessAgentGateway.class);
        AgentWorkspaceSyncService syncService = mock(AgentWorkspaceSyncService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        ChapterAccessService accessService = mock(ChapterAccessService.class);
        GenerationGuardService guard = mock(GenerationGuardService.class);
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        MangaAgentRunEventPublisher eventPublisher = mock(MangaAgentRunEventPublisher.class);
        MangaWorkflowOrchestrator orchestrator = mock(MangaWorkflowOrchestrator.class);
        MangaAgentConversationRegistry conversationRegistry = mock(MangaAgentConversationRegistry.class);
        MangaImageRepository imageRepository = mock(MangaImageRepository.class);
        CharacterProfileService characterProfileService = mock(CharacterProfileService.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        AgentRunToolStatus toolStatus = new AgentRunToolStatus();
        properties.getAgent().setRunTimeoutSeconds(5);
        properties.getDeepseek().setModel("deepseek-chat");
        User user = user(1L);
        Chapter chapter = chapter(user);
        MangaAgentConversation conversation = conversation(user, chapter);
        when(accessService.requireVisible(7L, 1L)).thenReturn(chapter);
        when(imageRepository.findByChapterIdOrderByImageNumberAsc(7L)).thenReturn(List.of());
        when(characterProfileService.resolveEffective(7L)).thenReturn(Map.of("content", "", "source", "none"));
        when(conversationRegistry.activeOrCreate(7L, user)).thenReturn(conversation);
        when(apiKeyService.getDecryptedKey(user, "deepseek")).thenReturn("deepseek-key");
        when(apiKeyService.getDecryptedKey(user, "coze")).thenReturn("coze-key");
        MangaAgentConversationService conversationService =
                new MangaAgentConversationService(messageRepository, accessService);
        MangaAgentService service = new MangaAgentService(
                conversationService,
                conversationRegistry,
                gateway,
                new AgentModelSpecFactory(properties),
                syncService,
                apiKeyService,
                accessService,
                guard,
                properties,
                toolStatus,
                new AgentScopeEventMapper(),
                runService,
                eventPublisher,
                orchestrator,
                Executors.newSingleThreadExecutor()
        );
        return new Fixture(service, gateway, orchestrator, user);
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

    private static MangaAgentConversation conversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setId(99L);
        conversation.setConversationUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        conversation.setUser(user);
        conversation.setStory(chapter.getStory());
        conversation.setChapter(chapter);
        conversation.setTitle("测试对话");
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private record Fixture(MangaAgentService service,
                           HarnessAgentGateway gateway,
                           MangaWorkflowOrchestrator orchestrator,
                           User user) {
    }
}
