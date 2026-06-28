package com.artverse.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentUserInputRequiredExceptionTest {

    @Test
    void carriesRequestPayload() {
        AgentUserInputRequest request = new AgentUserInputRequest(
                "Choose an option",
                List.of(new AgentUserInputRequest.Option("a", "A", "first", true)),
                false,
                "need confirmation"
        );

        AgentUserInputRequiredException ex = new AgentUserInputRequiredException(request);

        assertThat(ex.request()).isEqualTo(request);
        assertThat(ex).hasMessage("Agent requires user input");
    }
}
