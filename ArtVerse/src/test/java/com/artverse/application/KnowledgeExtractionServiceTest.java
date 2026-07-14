package com.artverse.application;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeExtractionServiceTest {

    @Test
    void blankChapterSupersedesPendingCandidatesWithoutCallingModel() {
        Fixture fixture = fixture("", "");
        Runnable ownership = mock(Runnable.class);
        when(fixture.candidateService.replaceChapterExtraction(7L, 1L, List.of()))
                .thenReturn(List.of());

        int count = fixture.service.extract(event(), ownership);

        assertThat(count).isZero();
        verify(ownership).run();
        verify(fixture.candidateService).replaceChapterExtraction(7L, 1L, List.of());
        verify(fixture.apiKeyService, never()).requireActiveUserProviderConfig(any(), any(), any());
        verify(fixture.gateway, never()).generateStructured(any(), any());
    }

    @Test
    void nonBlankChapterUsesByokAndPersistsOnlyStructuredCandidates() {
        Fixture fixture = fixture("角色阿青明确害怕海水。", "");
        Runnable ownership = mock(Runnable.class);
        UserProviderConfig provider = new UserProviderConfig(
                "llm", "deepseek", "User LLM", "sk-user",
                "https://api.deepseek.com", "deepseek-chat", 42L);
        AgentModelSpec spec = new AgentModelSpec(
                "deepseek", "https://api.deepseek.com", "deepseek-chat", "hash");
        KnowledgeCandidateService.ExtractedCandidate candidate =
                new KnowledgeCandidateService.ExtractedCandidate(
                        "CHARACTER_CARD", "阿青的弱点", "阿青害怕海水。", "怕水",
                        Map.of(), 4, 1, null);
        when(fixture.apiKeyService.requireActiveUserProviderConfig(
                fixture.user, ApiKeyService.SLOT_LLM, "An active user-owned LLM configuration is required for knowledge extraction."))
                .thenReturn(provider);
        when(fixture.modelSpecFactory.fromProviderConfig(provider)).thenReturn(spec);
        when(fixture.gateway.generateStructured(any(AgentRunRequest.class),
                eq(KnowledgeExtractionService.ExtractionResult.class)))
                .thenReturn(Mono.just(new KnowledgeExtractionService.ExtractionResult(List.of(candidate))));
        when(fixture.candidateService.replaceChapterExtraction(7L, 1L, List.of(candidate)))
                .thenReturn(Collections.singletonList(null));

        int count = fixture.service.extract(event(), ownership);

        assertThat(count).isEqualTo(1);
        verify(ownership).run();
        verify(fixture.candidateService).replaceChapterExtraction(7L, 1L, List.of(candidate));
    }

    private Fixture fixture(String novel, String scenes) {
        UserRepository userRepository = mock(UserRepository.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AgentModelSpecFactory modelSpecFactory = mock(AgentModelSpecFactory.class);
        AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
        KnowledgeCandidateService candidateService = mock(KnowledgeCandidateService.class);
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setNovelContent(novel);
        chapter.setScenesText(scenes);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(chapterRepository.findByIdForIdempotencyAndUserId(7L, 1L))
                .thenReturn(Optional.of(chapter));
        KnowledgeExtractionService service = new KnowledgeExtractionService(
                userRepository, chapterRepository, apiKeyService, modelSpecFactory,
                gateway, candidateService, new ObjectMapper(), new ArtVerseProperties());
        return new Fixture(service, apiKeyService, modelSpecFactory, gateway,
                candidateService, user);
    }

    private AgentOutboxService.OutboxEvent event() {
        return new AgentOutboxService.OutboxEvent(
                11L, "CHAPTER", "7", "CHAPTER_CONTENT_CHANGED",
                Map.of("user_id", 1L, "story_id", 3L, "chapter_id", 7L),
                1, 2L, OffsetDateTime.now().plusSeconds(90));
    }

    private record Fixture(
            KnowledgeExtractionService service,
            ApiKeyService apiKeyService,
            AgentModelSpecFactory modelSpecFactory,
            AgentScopeHarnessAgentGateway gateway,
            KnowledgeCandidateService candidateService,
            User user
    ) {
    }
}
