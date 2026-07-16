package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtVerseSkillRegistryTest {

    @Test
    void loadsBusinessSkillCatalogFromClasspath() {
        ArtVerseSkillRegistry registry = registry(true);

        assertThat(registry.publishedSkills()).hasSize(8);
        assertThat(registry.requirePublished("manga.storyboard-design").resources())
                .containsKey("manga/common-rules.md");
        assertThat(registry.requirePublished("novel.chapter-writing").promptTemplate())
                .contains("write complete chapter prose");
    }

    @Test
    void mapsStoryboardTaskToTwoPublishedSkills() {
        ArtVerseSkillRegistry registry = registry(true);

        var selection = registry.selectionForTask(AgentTaskType.MANGA_STORYBOARD);

        assertThat(selection.skillKeys())
                .containsExactly("manga.storyboard-design", "manga.visual-prompting");
    }

    @Test
    void resolvesExplicitNovelSelectionFromRequest() {
        ArtVerseSkillRegistry registry = registry(true);
        var explicitSelection = registry.selectionForNovelMode(NovelBusinessSkillMode.PROSE_POLISH);
        AgentRunRequest request = new AgentRunRequest(
                "1",
                3L,
                7L,
                AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "polish this paragraph")),
                Map.of(),
                null,
                "",
                null,
                null,
                explicitSelection
        );

        var resolved = registry.resolveSelection(request);

        assertThat(resolved.skillKeys()).containsExactly("novel.prose-polish");
    }

    private ArtVerseSkillRegistry registry(boolean enabled) {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setBusinessSkillsEnabled(enabled);
        ArtVerseSkillRegistry registry = new ArtVerseSkillRegistry(new DefaultResourceLoader(), properties);
        registry.initialize();
        return registry;
    }
}
