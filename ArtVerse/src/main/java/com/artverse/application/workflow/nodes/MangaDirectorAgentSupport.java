package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MangaDirectorAgentSupport {

    private final MangaAgentConversationService conversationService;
    private final AgentWorkspaceSyncService workspaceSyncService;
    private final ApiKeyService apiKeyService;

    public List<AgentMessage> prepareAgentMessages(MangaWorkflowExecutionContext context) {
        conversationService.saveMessage(
                context.conversation(),
                MessageRole.USER,
                context.message(),
                context.requestId()
        );
        List<MangaAgentMessage> history = conversationService.listMessages(context.conversation());
        return conversationService.buildMessages(
                context.chapter(),
                context.user(),
                history,
                context.message(),
                context.requestId()
        );
    }

    public AgentRunRequest buildRunRequest(MangaWorkflowExecutionContext context, List<AgentMessage> messages) {
        User user = context.user();
        Chapter chapter = context.chapter();
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of("coze_api_key", blankIfNull(apiKeyService.getDecryptedKey(user, "coze"))),
                context.modelSpec(),
                context.deepseekApiKey(),
                context.requestId(),
                context.conversation().getConversationUuid()
        );
    }

    public void syncWorkspace(MangaWorkflowExecutionContext context) {
        workspaceSyncService.syncMangaDirectorKnowledge(
                context.chapter().getId(),
                String.valueOf(context.user().getId())
        );
    }

    public void throwIfWaitingForUser(MangaWorkflowExecutionContext context) {
        AgentUserInputRequest waiting = context.toolState().userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    public void saveReply(MangaWorkflowExecutionContext context, String reply) {
        conversationService.saveMessage(
                context.conversation(),
                MessageRole.ASSISTANT,
                reply,
                context.requestId()
        );
    }

    public Map<String, Object> fallbackAfterToolSuccess(MangaWorkflowExecutionContext context, String error) {
        return conversationService.fallbackAfterToolSuccess(
                context.conversation(),
                context.requestId(),
                context.toolState(),
                error
        );
    }

    public void saveFailureMessage(MangaWorkflowExecutionContext context, String error) {
        conversationService.saveFailureMessage(context.conversation(), error, context.requestId());
    }

    public Optional<AgentRunEvent> mapAgentScopeEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            return Optional.of(new AgentRunEvent(
                    "run_started", "started", "agent started",
                    null, "running", null,
                    Map.of("agent", start.getName()),
                    OffsetDateTime.now()
            ));
        }
        if (event instanceof ModelCallStartEvent) {
            return Optional.of(AgentRunEvent.of("model_started", "thinking", "model is analyzing the chapter"));
        }
        if (event instanceof ModelCallEndEvent) {
            return Optional.of(AgentRunEvent.of("model_finished", "thinking", "model analysis complete"));
        }
        if (event instanceof ThinkingBlockStartEvent) {
            return Optional.of(AgentRunEvent.of("thinking_started", "thinking", "agent is reasoning"));
        }
        if (event instanceof ThinkingBlockDeltaEvent) {
            return Optional.empty();
        }
        if (event instanceof RequireExternalExecutionEvent ext) {
            return Optional.of(new AgentRunEvent(
                    "external_exec_required", "waiting_input", "waiting for user confirmation",
                    null, "waiting", null,
                    Map.of("toolCalls", ext.getToolCalls()),
                    OffsetDateTime.now()
            ));
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_started",
                    labelForTool(tool.getToolCallName(), "preparing"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_ready",
                    labelForTool(tool.getToolCallName(), "tool args ready"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_started",
                    labelForTool(tool.getToolCallName(), "running"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool(
                    "tool_finished",
                    labelForTool(tool.getToolCallName(), "tool finished"),
                    tool.getToolCallName(),
                    status,
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = text.getDelta();
            return delta == null || delta.isBlank() ? Optional.empty() : Optional.of(AgentRunEvent.text(delta));
        }
        if (event instanceof AgentResultEvent) {
            return Optional.of(AgentRunEvent.of("reply_ready", "replying", "final reply generated"));
        }
        if (event instanceof AgentEndEvent) {
            return Optional.of(AgentRunEvent.of("run_finished", "finished", "agent finished"));
        }
        return Optional.empty();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private String labelForTool(String toolName, String prefix) {
        return prefix + ": " + switch (toolName == null ? "" : toolName) {
            case "get_chapter_context" -> "read chapter context";
            case "generate_storyboard" -> "generate storyboard";
            case "save_storyboard" -> "save storyboard";
            case "save_structured_storyboard" -> "save structured storyboard";
            case "ask_user" -> "ask user";
            default -> toolName == null || toolName.isBlank() ? "tool" : toolName;
        };
    }
}
