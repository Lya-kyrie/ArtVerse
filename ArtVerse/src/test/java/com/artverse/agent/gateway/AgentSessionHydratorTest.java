package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSessionHydratorTest {

    @Test
    void healthyStateSendsOnlySystemDataAndCurrentUserMessage() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.exists(anyString(), anyString())).thenReturn(true);
        AgentSessionHydrator hydrator = new AgentSessionHydrator(store);
        AgentRunRequest request = request(List.of(
                new AgentMessage("system", "chapter rules"),
                new AgentMessage("user", "old question"),
                new AgentMessage("assistant", "old answer"),
                new AgentMessage("system", "rag data"),
                new AgentMessage("user", "current question")
        ));

        assertThat(hydrator.messagesFor(request))
                .extracting(AgentMessage::content)
                .containsExactly("chapter rules", "rag data", "current question");
    }

    @Test
    void missingStateKeepsDatabaseRecoveryHistory() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.exists(anyString(), anyString())).thenReturn(false);
        AgentSessionHydrator hydrator = new AgentSessionHydrator(store);
        List<AgentMessage> history = List.of(
                new AgentMessage("system", "rules"),
                new AgentMessage("user", "old"),
                new AgentMessage("assistant", "answer"),
                new AgentMessage("user", "current")
        );

        assertThat(hydrator.messagesFor(request(history))).containsExactlyElementsOf(history);
    }

    private AgentRunRequest request(List<AgentMessage> messages) {
        return new AgentRunRequest("1", 2L, 3L, AgentTaskType.MANGA_CONVERSATION,
                messages, Map.of(), null, "key", UUID.randomUUID(), UUID.randomUUID());
    }
}
