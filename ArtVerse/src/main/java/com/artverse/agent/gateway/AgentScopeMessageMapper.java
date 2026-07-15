package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentDataBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Maps ArtVerse messages to the input shape expected by AgentScope Harness agents. */
final class AgentScopeMessageMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    AgentScopeCallMessages map(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new AgentScopeCallMessages("", List.of());
        }
        List<Msg> mapped = new ArrayList<>(messages.size());
        List<String> systemContent = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("Agent message must not be null");
            }
            if ("system".equalsIgnoreCase(message.role())) {
                if (message.dataBlock() != null) {
                    throw new IllegalArgumentException("Agent DataBlock messages must use the USER role");
                }
                systemContent.add(message.content() == null ? "" : message.content());
                continue;
            }
            mapped.add(toAgentScopeMessage(message));
        }
        return new AgentScopeCallMessages(String.join("\n\n", systemContent), mapped);
    }

    private Msg toAgentScopeMessage(AgentMessage message) {
        MsgRole role = toAgentScopeRole(message.role());
        if (message.dataBlock() == null) {
            return Msg.builder().role(role).textContent(message.content() == null ? "" : message.content()).build();
        }
        // AgentScope 2.0 validates SYSTEM messages as text-only. USER is the
        // supported envelope for non-text content, including DataBlock.
        // The block itself is server-constructed and is introduced by the
        // system prompt as authoritative workflow context.
        if (role != MsgRole.USER) {
            throw new IllegalArgumentException("Agent DataBlock messages must use the USER role");
        }
        return Msg.builder().role(role).content(toDataBlock(message.dataBlock())).build();
    }

    private DataBlock toDataBlock(AgentDataBlock block) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(block.payload());
            String data = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return DataBlock.builder()
                    .id(block.id())
                    .name(block.name())
                    .source(Base64Source.builder().mediaType("application/json").data(data).build())
                    .build();
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to encode AgentScope DataBlock " + block.name(), error);
        }
    }

    private MsgRole toAgentScopeRole(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Agent message role is required");
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            default -> throw new IllegalArgumentException("Unsupported AgentScope message role: " + role);
        };
    }
}
