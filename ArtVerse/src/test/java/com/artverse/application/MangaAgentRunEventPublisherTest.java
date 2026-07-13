package com.artverse.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MangaAgentRunEventPublisherTest {

    @Test
    void doesNotSendAfterEmitterHasCompleted() throws Exception {
        MangaAgentRunService runService = mock(MangaAgentRunService.class);
        SseEmitter emitter = mock(SseEmitter.class);
        MangaAgentRunEventPublisher publisher = new MangaAgentRunEventPublisher(
                runService, new ObjectMapper(), new AgUiEventFactory()
        );
        MangaAgentRunEventPublisher.RunEventSink sink = publisher.newSink(emitter);

        sink.complete();
        sink.sendError(null, UUID.randomUUID(), "late failure");

        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
