package com.artverse.application.workflow;

import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.domain.MangaAgentRun;

public record MangaWorkflowStreamContext(
        MangaAgentRun run,
        MangaAgentRunEventPublisher.RunEventSink sink
) {
}
