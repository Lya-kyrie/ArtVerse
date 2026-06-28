package com.artverse.application.workflow.nodes;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunTerminatedExceptionTest {

    @Test
    void carriesRunContext() {
        UUID requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AgentRunTerminatedException ex = new AgentRunTerminatedException(requestId, 1L, 7L);

        assertThat(ex).hasMessage("Agent run terminated");
        assertThat(ex.requestId()).isEqualTo(requestId);
        assertThat(ex.userId()).isEqualTo(1L);
        assertThat(ex.chapterId()).isEqualTo(7L);
    }
}
