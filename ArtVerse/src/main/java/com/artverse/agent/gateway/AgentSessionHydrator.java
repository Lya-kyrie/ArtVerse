package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import io.agentscope.core.state.AgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Avoids sending DB history again when AgentScope state is healthy, while
 * retaining the recent DB history as a deterministic recovery payload.
 */
@Slf4j
@Component
public class AgentSessionHydrator {

    private final AgentStateStore stateStore;

    public AgentSessionHydrator(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public List<AgentMessage> messagesFor(AgentRunRequest request) {
        List<AgentMessage> messages = request.messages() == null ? List.of() : request.messages();
        if (!hasState(request)) {
            return messages;
        }
        int currentUserIndex = lastUserIndex(messages);
        if (currentUserIndex < 0) {
            return messages.stream()
                    .filter(message -> "system".equalsIgnoreCase(message.role()))
                    .toList();
        }
        List<AgentMessage> compact = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if ("system".equalsIgnoreCase(message.role()) || index == currentUserIndex) {
                compact.add(message);
            }
        }
        return List.copyOf(compact);
    }

    private boolean hasState(AgentRunRequest request) {
        try {
            return stateStore.exists(request.userId(), AgentScopeRuntimeContextFactory.createSessionId(request));
        } catch (Exception error) {
            log.warn("AgentState lookup failed; hydrating request {} from PostgreSQL history",
                    request.requestId());
            return false;
        }
    }

    private int lastUserIndex(List<AgentMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equalsIgnoreCase(messages.get(index).role())) {
                return index;
            }
        }
        return -1;
    }
}
