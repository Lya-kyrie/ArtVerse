package com.artverse.application.workflow.nodes;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
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
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentWorkspaceSyncService;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MangaDirectorAgentNode implements MangaWorkflowNodeHandler {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final AgentScopeHarnessAgentGateway harnessAgentGateway;
    private final AgentWorkspaceSyncService agentWorkspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final ArtVerseProperties properties;
    private final MangaAgentRunService mangaAgentRunService;

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.DIRECTOR;
    }

    @Override
    public Map<String, Object> run(MangaWorkflowExecutionContext context) {
        List<AgentMessage> messages = prepareAgentMessages(context);
        syncWorkspace(context);
        AgentRunRequest request = buildRunRequest(context, messages);
        try {
            String reply = harnessAgentGateway.generateText(request).block(agentRunTimeout());
            throwIfWaitingForUser(context);
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
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker open for agent LLM, fast-failing request={}", context.requestId());
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试");
        } catch (BusinessException e) {
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        context.conversation(), context.requestId(), context.toolState(), e.getMessage());
            }
            mangaAgentConversationService.saveFailureMessage(context.conversation(), e.getMessage(), context.requestId());
            throw e;
        } catch (Exception e) {
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        context.conversation(), context.requestId(), context.toolState(), error);
            }
            mangaAgentConversationService.saveFailureMessage(context.conversation(), error, context.requestId());
            throw new BusinessException(502, "Agent service failed: " + error);
        }
    }

    @Override
    public Map<String, Object> stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
        List<AgentMessage> messages = prepareAgentMessages(context);
        streamContext.sink().sendRunEvent(streamContext.run(), AgentRunEvent.step(
                MangaWorkflowNode.GENERATING.name(),
                "running",
                "正在调用智能体生成内容",
                Map.of("provider", context.modelSpec().provider(), "model", context.modelSpec().model())
        ));
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
                            throw new AgentRunTerminatedException(context.requestId(), context.user().getId(), context.chapter().getId());
                        }
                        if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                            reply.append(mapped.text());
                        }
                        streamContext.sink().sendRunEvent(streamContext.run(), mapped);
                    }))
                    .blockLast(agentRunTimeout());
            finished.set(true);
            throwIfWaitingForUser(context);
        } catch (AgentRunTerminatedException e) {
            return Map.of("reply", "");
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker open for agent LLM, fast-failing request={}", context.requestId());
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            if (mangaAgentRunService.isTerminal(context.requestId(), context.user().getId(), context.chapter().getId())) {
                return Map.of("reply", "");
            }
            String error = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        context.conversation(), context.requestId(), context.toolState(), error);
            }
            mangaAgentConversationService.saveFailureMessage(context.conversation(), error, context.requestId());
            throw new BusinessException(502, "Agent service failed: " + error);
        }

        String finalReply = reply.toString().trim();
        if (mangaAgentRunService.isTerminal(context.requestId(), context.user().getId(), context.chapter().getId())) {
            return Map.of("reply", "");
        }
        if (!finished.get() || finalReply.isBlank()) {
            if (context.toolState().hasSuccessfulMutatingTool()) {
                return mangaAgentConversationService.fallbackAfterToolSuccess(
                        context.conversation(),
                        context.requestId(),
                        context.toolState(),
                        "Agent returned empty response"
                );
            }
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

    private List<AgentMessage> prepareAgentMessages(MangaWorkflowExecutionContext context) {
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

    private AgentRunRequest buildRunRequest(MangaWorkflowExecutionContext context, List<AgentMessage> messages) {
        User user = context.user();
        Chapter chapter = context.chapter();
        return new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapter.getId(),
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of("coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))),
                context.modelSpec(),
                context.deepseekApiKey(),
                context.requestId(),
                context.conversation().getConversationUuid()
        );
    }

    private void syncWorkspace(MangaWorkflowExecutionContext context) {
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(
                context.chapter().getId(),
                String.valueOf(context.user().getId())
        );
    }

    private void throwIfWaitingForUser(MangaWorkflowExecutionContext context) {
        AgentUserInputRequest waiting = context.toolState().userInputRequest();
        if (waiting != null) {
            throw new AgentUserInputRequiredException(waiting);
        }
    }

    private Duration agentRunTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getRunTimeoutSeconds()));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }


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
        if (event instanceof RequireExternalExecutionEvent ext) {
            return Optional.of(new AgentRunEvent(
                    "external_exec_required", "waiting_input", "等待用户确认",
                    null, "waiting", null,
                    Map.of("toolCalls", ext.getToolCalls()),
                    OffsetDateTime.now()
            ));
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_started",
                    labelForTool(tool.getToolCallName(), "准备调用"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_ready",
                    labelForTool(tool.getToolCallName(), "工具参数已准备"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_started",
                    labelForTool(tool.getToolCallName(), "正在执行"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool(
                    "tool_finished",
                    labelForTool(tool.getToolCallName(), "工具执行完成"),
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

    private String labelForTool(String toolName, String prefix) {
        return prefix + "：" + switch (toolName == null ? "" : toolName) {
            case "get_chapter_context" -> "读取章节上下文";
            case "generate_storyboard" -> "生成分镜";
            case "save_storyboard" -> "保存分镜";
            case "save_structured_storyboard" -> "保存结构化分镜";
            case "ask_user" -> "询问用户";
            default -> toolName == null || toolName.isBlank() ? "工具" : toolName;
        };
    }}
