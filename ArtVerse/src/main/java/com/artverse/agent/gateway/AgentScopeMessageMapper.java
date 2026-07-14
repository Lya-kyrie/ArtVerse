package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps ArtVerse messages to the input shape expected by AgentScope Harness agents. */
final class AgentScopeMessageMapper {

    List<Msg> map(List<AgentMessage> messages) {
        List<String> systemMessages = new ArrayList<>();
        List<AgentMessage> inputMessages = new ArrayList<>();

        for (AgentMessage message : messages) {
            if ("system".equalsIgnoreCase(message.role())) {
                systemMessages.add(message.content());
            } else {
                inputMessages.add(message);
            }
        }

        String systemPrompt = String.join("\n\n", systemMessages);
        boolean hasSystemMessages = !systemMessages.isEmpty();
        if (inputMessages.isEmpty() && hasSystemMessages) {
            return List.of(toAgentScopeMessage("user", systemPrompt));
        }

        List<Msg> mapped = new ArrayList<>(inputMessages.size());
        for (int index = 0; index < inputMessages.size(); index++) {
            AgentMessage message = inputMessages.get(index);
            String content = index == 0 && hasSystemMessages
                    ? systemPrompt + "\n\n" + message.content()
                    : message.content();
            mapped.add(toAgentScopeMessage(message.role(), content));
        }
        return List.copyOf(mapped);
    }

    private Msg toAgentScopeMessage(String role, String content) {
        return Msg.builder()
                .role(toAgentScopeRole(role))
                .textContent(content)
                .build();
    }

    private MsgRole toAgentScopeRole(String role) {
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            default -> MsgRole.USER;
        };
    }
}
