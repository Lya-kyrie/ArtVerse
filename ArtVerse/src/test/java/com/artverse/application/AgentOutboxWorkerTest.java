package com.artverse.application;

import com.artverse.config.ArtVerseProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOutboxWorkerTest {

    @Test
    void successfulExtractionIsPublishedWithCurrentFence() {
        AgentOutboxService outbox = mock(AgentOutboxService.class);
        KnowledgeExtractionService extraction = mock(KnowledgeExtractionService.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentOutboxService.OutboxEvent event = event();
        when(outbox.claimBatch(any(), eq(8), eq(90))).thenReturn(List.of(event));
        when(extraction.extract(eq(event), any())).thenReturn(2);
        when(outbox.markPublished(eq(event), any())).thenReturn(true);
        AgentOutboxWorker worker = new AgentOutboxWorker(outbox, extraction, properties, registry);

        try {
            worker.dispatch();
        } finally {
            worker.close();
        }

        verify(outbox).markPublished(eq(event), any());
        assertThat(registry.get("artverse.agent.outbox.processed")
                .tag("event_type", "CHAPTER_CONTENT_CHANGED")
                .tag("outcome", "success").counter().count()).isEqualTo(1.0);
    }

    private AgentOutboxService.OutboxEvent event() {
        return new AgentOutboxService.OutboxEvent(
                11L, "CHAPTER", "7", "CHAPTER_CONTENT_CHANGED",
                Map.of("user_id", 1L, "story_id", 3L, "chapter_id", 7L),
                1, 2L, OffsetDateTime.now().plusSeconds(90));
    }
}
