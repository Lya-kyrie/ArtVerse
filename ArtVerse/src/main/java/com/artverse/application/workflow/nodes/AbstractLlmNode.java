package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for LLM-powered workflow nodes that share a common call pattern
 * but differ in system prompt and tool permissions.
 */
abstract class AbstractLlmNode implements MangaWorkflowNodeHandler {

    protected final MangaAgentConversationService mangaAgentConversationService;
    protected final AgentScopeHarnessAgentGateway harnessAgentGateway;
    protected final AgentWorkspaceSyncService agentWorkspaceSyncService;
    protected final ApiKeyService apiKeyService;
    protected final ArtVerseProperties properties;
    protected final MangaAgentRunService mangaAgentRunService;

    protected AbstractLlmNode(
            MangaAgentConversationService mangaAgentConversationService,
            AgentScopeHarnessAgentGateway harnessAgentGateway,
            AgentWorkspaceSyncService agentWorkspaceSyncService,
            ApiKeyService apiKeyService,
            ArtVerseProperties properties,
            MangaAgentRunService mangaAgentRunService) {
        this.mangaAgentConversationService = mangaAgentConversationService;
        this.harnessAgentGateway = harnessAgentGateway;
        this.agentWorkspaceSyncService = agentWorkspaceSyncService;
        this.apiKeyService = apiKeyService;
        this.properties = properties;
        this.mangaAgentRunService = mangaAgentRunService;
    }

    /**
     * @return the {@link AgentTaskType} that determines system prompt and tool group configuration.
     */
    public abstract AgentTaskType agentTaskType();

    // ---- run ----

    @Override
    public Map<String, Object> run(MangaWorkflowExecutionContext context) {
        List<AgentMessage> messages = prepareAgentMessages(context);
        syncWorkspace(context);
        AgentRunRequest request = buildRunRequest(context, messages);
        try {
            String reply = harnessAgentGateway.generateText(request).block(agentRunTimeout());
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            mangaAgentConversationService.saveMessage(
                    context.conversation(),
                    MessageRole.ASSISTANT,
                    reply,
                    context.requestId()
            );
            return Map.of("reply", reply);
        } catch (BusinessException e) {
            mangaAgentConversationService.saveFailureMessage(
                    context.conversation(), e.getMessage(), context.requestId());
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            mangaAgentConversationService.saveFailureMessage(
                    context.conversation(), error, context.requestId());
            throw new BusinessException(502, "Agent service failed: " + error);
        }
    }

    // ---- stream ----

    @Override
    public Map<String, Object> stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        List<AgentMessage> messages = prepareAgentMessages(context);
        syncWorkspace(context);
        AgentRunRequest request = buildRunRequest(context, messages);
        return executeStreamedRequest(context, streamContext, request);
    }

    private Map<String, Object> executeStreamedRequest(MangaWorkflowExecutionContext context,
                                                       MangaWorkflowStreamContext streamContext,
                                                       AgentRunRequest request) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> mapAgentScopeEvent(event).ifPresent(mapped -> {
                        if (mangaAgentRunService.isTerminal(
                                context.requestId(), context.user().getId(), context.chapter().getId())) {
                            throw new AgentRunTerminatedException();
                        }
                        if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                            reply.append(mapped.text());
                        }
                        streamContext.sink().sendRunEvent(streamContext.run(), mapped);
                    }))
                    .blockLast(agentRunTimeout());
            finished.set(true);
        } catch (AgentRunTerminatedException e) {
            return Map.of("reply", "");
        } catch (BusinessException e) {
            mangaAgentConversationService.saveFailureMessage(
                    context.conversation(), e.getMessage(), context.requestId());
            throw e;
        } catch (Exception e) {
            if (mangaAgentRunService.isTerminal(
                    context.requestId(), context.user().getId(), context.chapter().getId())) {
                return Map.of("reply", "");
            }
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            mangaAgentConversationService.saveFailureMessage(
                    context.conversation(), error, context.requestId());
            throw new BusinessException(502, "Agent service failed: " + error);
        }

        String finalReply = reply.toString().trim();
        if (mangaAgentRunService.isTerminal(
                context.requestId(), context.user().getId(), context.chapter().getId())) {
            return Map.of("reply", "");
        }
        if (!finished.get() || finalReply.isBlank()) {
            throw new BusinessException(502, "Agent returned empty response");
        }

        mangaAgentConversationService.saveMessage(
                context.conversation(),
                MessageRole.ASSISTANT,
                finalReply,
                context.requestId()
        );
        return Map.of("reply", finalReply);
    }

    // ---- shared helpers ----

    protected List<AgentMessage> prepareAgentMessages(MangaWorkflowExecutionContext context) {
        mangaAgentConversationService.saveMessage(
                context.conversation(),
                MessageRole.USER,
                context.message(),
                context.requestId()
        );
        List<MangaAgentMessage> history = mangaAgentConversationService.listMessages(context.conversation());
        return mangaAgentConversationService.buildMessages(
                context.chapter(),
                context.user(),
                history,
                context.message(),
                context.requestId()
        );
    }

    protected AgentRunRequest buildRunRequest(MangaWorkflowExecutionContext context, List<AgentMessage> messages) {
        User user = context.user();
        Chapter chapter = context.chapter();
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                agentTaskType(),
                messages,
                Map.of("coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))),
                context.modelSpec(),
                context.deepseekApiKey(),
                context.requestId(),
                context.conversation().getConversationUuid()
        );
    }

    protected void syncWorkspace(MangaWorkflowExecutionContext context) {
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(
                context.chapter().getId(),
                String.valueOf(context.user().getId())
        );
    }

    protected Duration agentRunTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getRunTimeoutSeconds()));
    }

    protected String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    // ---- event mapping (same generic mapping as Director, tool labels are shared) ----

    private Optional<AgentRunEvent> mapAgentScopeEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            return Optional.of(new AgentRunEvent(
                    "run_started", "started", "智能体已启动",
                    null, "running", null,
                    Map.of("agent", start.getName()),
                    OffsetDateTime.now()
            ));
        }
        if (event instanceof ModelCallStartEvent) {
            return Optional.of(AgentRunEvent.of("model_started", "thinking", "模型正在分析当前章节"));
        }
        if (event instanceof ModelCallEndEvent) {
            return Optional.of(AgentRunEvent.of("model_finished", "thinking", "模型分析完成"));
        }
        if (event instanceof ThinkingBlockStartEvent) {
            return Optional.of(AgentRunEvent.of("thinking_started", "thinking", "智能体正在推理"));
        }
        if (event instanceof ThinkingBlockDeltaEvent) {
            return Optional.empty();
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_started",
                    toolLabelForTool(tool.getToolCallName(), "准备调用"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_ready",
                    toolLabelForTool(tool.getToolCallName(), "工具参数已准备"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_started",
                    toolLabelForTool(tool.getToolCallName(), "正在执行"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool(
                    "tool_finished",
                    toolLabelForTool(tool.getToolCallName(), "工具执行完成"),
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
            return Optional.of(AgentRunEvent.of("reply_ready", "replying", "最终回复已生成"));
        }
        if (event instanceof AgentEndEvent) {
            return Optional.of(AgentRunEvent.of("run_finished", "finished", "智能体运行结束"));
        }
        return Optional.empty();
    }

    private String toolLabelForTool(String toolName, String prefix) {
        return prefix + "：" + switch (toolName == null ? "" : toolName) {
            case "get_chapter_context" -> "读取章节上下文";
            case "generate_storyboard" -> "生成分镜";
            case "save_storyboard" -> "保存分镜";
            case "save_structured_storyboard" -> "保存结构化分镜";
            case "ask_user" -> "询问用户";
            default -> toolName == null || toolName.isBlank() ? "工具" : toolName;
        };
    }

    private static class AgentRunTerminatedException extends RuntimeException {
    }
}
