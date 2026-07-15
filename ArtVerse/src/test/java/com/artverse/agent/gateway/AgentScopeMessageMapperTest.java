package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentDataBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentScopeMessageMapperTest {

    private final AgentScopeMessageMapper mapper = new AgentScopeMessageMapper();

    @Test
    void mapsMessagesWithoutSystemPrompt() {
        AgentScopeCallMessages result = mapper.map(List.of(
                new AgentMessage("user", "question"),
                new AgentMessage("assistant", "answer")));

        assertEquals("", result.systemContent());
        assertMessage(result.inputMessages().get(0), MsgRole.USER, "question");
        assertMessage(result.inputMessages().get(1), MsgRole.ASSISTANT, "answer");
    }

    @Test
    void routesSystemMessagesThroughTheSystemPromptChannel() {
        AgentScopeCallMessages result = mapper.map(List.of(
                new AgentMessage("system", "policy"),
                new AgentMessage("user", "question"),
                new AgentMessage("system", "context"),
                new AgentMessage("assistant", "answer")));

        assertEquals("policy\n\ncontext", result.systemContent());
        assertEquals(2, result.inputMessages().size());
        assertMessage(result.inputMessages().get(0), MsgRole.USER, "question");
        assertMessage(result.inputMessages().get(1), MsgRole.ASSISTANT, "answer");
        assertEquals(false, result.inputMessages().stream().anyMatch(message -> message.getRole() == MsgRole.SYSTEM));
    }

    @Test
    void routesSystemOnlyInputWithoutCreatingAPretendUserMessage() {
        AgentScopeCallMessages result = mapper.map(List.of(
                new AgentMessage("system", "policy"),
                new AgentMessage("SYSTEM", "context")));

        assertEquals("policy\n\ncontext", result.systemContent());
        assertEquals(List.of(), result.inputMessages());
    }

    @Test
    void rejectsUnknownRoleWithoutChangingInput() {
        List<AgentMessage> input = new ArrayList<>(List.of(
                new AgentMessage("tool", "result")));

        assertThrows(IllegalArgumentException.class, () -> mapper.map(input));
        assertEquals(List.of(new AgentMessage("tool", "result")), input);
    }

    @Test
    void mapsServerDataAsNativeDataBlock() {
        AgentScopeCallMessages result = mapper.map(List.of(new AgentMessage("user",
                new AgentDataBlock("chapter_snapshot", "chapter-7", java.util.Map.of("chapter", 7)))));

        Msg message = result.inputMessages().getFirst();
        assertEquals(MsgRole.USER, message.getRole());
        assertEquals(1, message.getContentBlocks(DataBlock.class).size());
        assertEquals("chapter_snapshot", message.getFirstContentBlock(DataBlock.class).getName());
    }

    private void assertMessage(Msg message, MsgRole role, String content) {
        assertEquals(role, message.getRole());
        assertEquals(content, message.getTextContent());
    }
}
