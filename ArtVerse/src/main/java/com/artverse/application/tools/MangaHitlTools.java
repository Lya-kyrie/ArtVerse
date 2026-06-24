package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.AgentUserInputRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MangaHitlTools {

    private final AgentToolAuditService agentToolAuditService;
    private final MangaToolSupport support;

    @Tool(
            name = "ask_user",
            description = "Pause the manga agent and ask the user to choose between options before continuing. Use this when a creative or workflow decision cannot be made safely.",
            readOnly = true
    )
    public Map<String, Object> askUser(
            @ToolParam(name = "question", description = "Question to show to the user") String question,
            @ToolParam(name = "options", description = "Options as a list of strings or objects with label/description/recommended") Object options,
            @ToolParam(name = "allow_free_text", description = "Whether the user may type a custom answer") Boolean allowFreeText,
            @ToolParam(name = "reason", description = "Short reason why user input is needed") String reason,
            RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return agentToolAuditService.around("ask_user", context.userId(), context.chapterId(), runtimeContext, () -> {
            AgentUserInputRequest request = buildUserInputRequest(question, options, allowFreeText, reason);
            support.requestUserInput(context, request);
            throw new ToolSuspendException("Waiting for user input");
        });
    }

    private AgentUserInputRequest buildUserInputRequest(String question, Object rawOptions,
                                                        Boolean allowFreeText, String reason) {
        List<AgentUserInputRequest.Option> options = support.normalizeOptions(rawOptions);
        if (options.isEmpty()) {
            options = List.of(
                    new AgentUserInputRequest.Option("a", "Continue with default", "Let the agent decide based on context", true),
                    new AgentUserInputRequest.Option("b", "Give suggestion first", "Agent explains recommendation before acting", false)
            );
        }
        return new AgentUserInputRequest(
                question == null || question.isBlank() ? "Please confirm how to proceed." : question.trim(),
                options,
                Boolean.TRUE.equals(allowFreeText),
                reason == null ? "" : reason.trim()
        );
    }
}
