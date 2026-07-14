package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentScopeMessageMapperTest {

    private final AgentScopeMessageMapper mapper = new AgentScopeMessageMapper();

    @Test
    void mapsMessagesWithoutSystemPrompt() {
        List<Msg> result = mapper.map(List.of(
                new AgentMessage("user", "question"),
                new AgentMessage("assistant", "answer")));

        assertMessage(result.get(0), MsgRole.USER, "question");
        assertMessage(result.get(1), MsgRole.ASSISTANT, "answer");
    }

    @Test
    void prependsAllSystemMessagesToFirstInputMessage() {
        List<Msg> result = mapper.map(List.of(
                new AgentMessage("system", "policy"),
                new AgentMessage("user", "question"),
                new AgentMessage("system", "context"),
                new AgentMessage("assistant", "answer")));

        assertEquals(2, result.size());
        assertMessage(result.get(0), MsgRole.USER, "policy\n\ncontext\n\nquestion");
        assertMessage(result.get(1), MsgRole.ASSISTANT, "answer");
    }

    @Test
    void convertsSystemOnlyInputToUserMessage() {
        List<Msg> result = mapper.map(List.of(
                new AgentMessage("system", "policy"),
                new AgentMessage("SYSTEM", "context")));

        assertEquals(1, result.size());
        assertMessage(result.get(0), MsgRole.USER, "policy\n\ncontext");
    }

    @Test
    void defaultsUnknownRoleToUserWithoutChangingInput() {
        List<AgentMessage> input = new ArrayList<>(List.of(
                new AgentMessage("tool", "result")));

        List<Msg> result = mapper.map(input);

        assertMessage(result.get(0), MsgRole.USER, "result");
        assertEquals(List.of(new AgentMessage("tool", "result")), input);
    }

    private void assertMessage(Msg message, MsgRole role, String content) {
        assertEquals(role, message.getRole());
        assertEquals(content, message.getTextContent());
    }
}
