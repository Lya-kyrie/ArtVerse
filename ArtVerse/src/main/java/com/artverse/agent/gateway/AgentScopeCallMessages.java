package com.artverse.agent.gateway;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * Separates a per-call system prompt contribution from ordinary conversation
 * messages. AgentScope accepts the former only through its system-prompt
 * lifecycle, never through PreCallEvent.inputMessages.
 */
record AgentScopeCallMessages(String systemContent, List<Msg> inputMessages) {

    AgentScopeCallMessages {
        systemContent = systemContent == null ? "" : systemContent;
        inputMessages = inputMessages == null ? List.of() : List.copyOf(inputMessages);
    }
}
