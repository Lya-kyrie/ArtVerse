package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolAuditServiceTest {

    @Test
    void extractsRequestIdFromRuntimeContext() {
        AgentRunToolStatus status = new AgentRunToolStatus();
        AgentToolAuditService service = new AgentToolAuditService(status);
        UUID requestId = UUID.randomUUID();

        try (AgentRunToolStatus.RunScope ignored = status.start(1L, 7L, requestId)) {
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .sessionId("u-1-story-2-chapter-7-manga-director")
                    .userId("1")
                    .put(MangaAgentRuntimeContext.class, new MangaAgentRuntimeContext(
                            1L, 2L, 7L, UUID.randomUUID(), requestId, ""))
                    .build();

            Callable<Map<String, Object>> action = () -> Map.of("saved", true);
            service.around("save_structured_storyboard", 1L, 7L, runtimeContext, action);
            assertThat(ignored.state().events()).hasSize(1);
        }
    }
}
