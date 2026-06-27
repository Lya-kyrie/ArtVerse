package com.artverse.application;

import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgUiEventFactoryTest {

    @Test
    void runStartedCarriesRouteIntoAgUiPayload() {
        AgUiEventFactory factory = new AgUiEventFactory();
        MangaAgentRun run = run(MangaWorkflowRoute.REVIEW);

        Map<String, Object> event = factory.runStarted(run, UUID.randomUUID(), "hello");

        assertThat(event.get("input")).isInstanceOf(Map.class);
        Map<?, ?> input = (Map<?, ?>) event.get("input");
        Map<?, ?> state = (Map<?, ?>) input.get("state");
        assertThat(state.get("route")).isEqualTo("REVIEW");
    }

    @Test
    void stateSnapshotCarriesRouteIntoAgUiPayload() {
        AgUiEventFactory factory = new AgUiEventFactory();
        MangaAgentRun run = run(MangaWorkflowRoute.HITL);

        Map<String, Object> event = factory.stateSnapshot(run, UUID.randomUUID(), "RUNNING", "working");

        Map<?, ?> snapshot = (Map<?, ?>) event.get("snapshot");
        assertThat(snapshot.get("route")).isEqualTo("HITL");
    }

    private MangaAgentRun run(MangaWorkflowRoute route) {
        User user = new User();
        user.setId(1L);
        Story story = new Story();
        story.setId(2L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(3L);
        chapter.setStory(story);

        MangaAgentRun run = new MangaAgentRun();
        run.setUser(user);
        run.setStory(story);
        run.setChapter(chapter);
        run.setRequestId(UUID.randomUUID());
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setRoute(route);
        return run;
    }
}
