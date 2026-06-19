package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MangaAgentToolFactoryTest {

    @Test
    void generateStoryboardUsesGenerationGuard() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());
        Chapter chapter = chapterWithOwner(7L, 1L);

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(sceneService.generateScenes(7L, "coze-key")).thenReturn(List.of("scene 1"));
        when(generationGuardService.executeSceneGeneration(eq(1L), eq(7L), any()))
                .thenAnswer(invocation -> invocation.<Callable<Map<String, Object>>>getArgument(2).call());

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                1L
        );

        Map<String, Object> result = tools.generateStoryboard();

        assertThat(result).containsEntry("scenes_count", 1);
        verify(generationGuardService).executeSceneGeneration(eq(1L), eq(7L), any());
        verify(sceneService).generateScenes(7L, "coze-key");
    }

    @Test
    void generateStoryboardRejectsDifferentUserBeforeCallingGuard() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapterWithOwner(7L, 1L)));

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                2L
        );

        assertThatThrownBy(tools::generateStoryboard)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Forbidden");
        verifyNoInteractions(generationGuardService, sceneService);
    }

    @Test
    void saveStoryboardRejectsDifferentUser() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapterWithOwner(7L, 1L)));

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                2L
        );

        assertThatThrownBy(() -> tools.saveStoryboard(List.of("scene 1")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Forbidden");
        verifyNoInteractions(sceneService);
    }

    @Test
    void saveStructuredStoryboardNormalizesAndSavesScenes() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        MangaImageRepository mangaImageRepository = mock(MangaImageRepository.class);
        SceneService sceneService = mock(SceneService.class);
        StructuredStoryboardService structuredStoryboardService = mock(StructuredStoryboardService.class);
        GenerationGuardService generationGuardService = mock(GenerationGuardService.class);
        AgentToolAuditService auditService = new AgentToolAuditService(new AgentRunToolStatus());
        Chapter chapter = chapterWithOwner(7L, 1L);
        Object pages = List.of(Map.of("panels", List.of("a", "b", "c", "d")));
        List<String> scenes = List.of("第1页：【第1格】a【第2格】b【第3格】c【第4格】d");

        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));
        when(structuredStoryboardService.normalize(pages, 1)).thenReturn(scenes);
        when(sceneService.updateScenes(7L, scenes)).thenReturn(scenes);

        MangaAgentToolFactory.Tools tools = tools(
                chapterRepository,
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                generationGuardService,
                auditService,
                1L
        );

        Map<String, Object> result = tools.saveStructuredStoryboard(pages);

        assertThat(result).containsEntry("scenes_count", 1);
        verify(structuredStoryboardService).normalize(pages, 1);
        verify(sceneService).updateScenes(7L, scenes);
    }

    private MangaAgentToolFactory.Tools tools(ChapterRepository chapterRepository,
                                             MangaImageRepository mangaImageRepository,
                                             SceneService sceneService,
                                             StructuredStoryboardService structuredStoryboardService,
                                             GenerationGuardService generationGuardService,
                                             AgentToolAuditService auditService,
                                             Long userId) {
        MangaAgentToolFactory factory = new MangaAgentToolFactory(
                mangaImageRepository,
                sceneService,
                structuredStoryboardService,
                new ChapterAccessService(chapterRepository),
                generationGuardService,
                auditService
        );
        return (MangaAgentToolFactory.Tools) factory.create("coze-key", 7L, userId);
    }

    private Chapter chapterWithOwner(Long chapterId, Long ownerId) {
        User user = new User();
        user.setId(ownerId);
        Story story = new Story();
        story.setId(3L);
        story.setTitle("Story");
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setImageCount(1);
        chapter.setColorMode(ColorMode.BW);
        chapter.setNovelContent("source");
        return chapter;
    }
}
