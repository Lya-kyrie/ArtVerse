package com.artverse.application.workflow.nodes;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentRunEvent;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentScopeEventMapper;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.AgentWorkspaceSyncService;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowNode;
import com.artverse.application.workflow.MangaWorkflowNodeHandler;
import com.artverse.application.workflow.MangaWorkflowResult;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowStreamContext;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.ToolSuspendException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class MangaDirectorAgentNode implements MangaWorkflowNodeHandler {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final HarnessAgentGateway harnessAgentGateway;
    private final AgentWorkspaceSyncService agentWorkspaceSyncService;
    private final ApiKeyService apiKeyService;
    private final ArtVerseProperties properties;
    private final AgentScopeEventMapper agentScopeEventMapper;
    private final MangaAgentRunService mangaAgentRunService;

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.DIRECTOR;
    }

    @Override
    public List<String> activeToolGroups() {
        return List.of(
                com.artverse.agents.MangaAgentToolkitFactory.CONTEXT_TOOLS,
                com.artverse.agents.MangaAgentToolkitFactory.STORYBOARD_TOOLS,
                com.artverse.agents.MangaAgentToolkitFactory.HITL_TOOLS
        );
    }

    @Override
    public MangaWorkflowResult run(MangaWorkflowExecutionContext context) {
        List<AgentMessage> messages = prepareAgentMessages(context);
        syncWorkspace(context);
        AgentRunRequest request = buildRunRequest(context, messages);
        try {
            Msg result = harnessAgentGateway.generate(request).block(agentRunTimeout());
            throwIfWaitingForUser(context, result);
            String reply = result == null ? null : result.getTextContent();
            if (reply == null || reply.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            mangaAgentConversationService.saveMessage(
                    context.conversation(),
                    MessageRole.ASSISTANT,
                    reply,
                    context.requestId()
            );
            return MangaWorkflowResult.success(reply);
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(context, null);
            throw new BusinessException(502, "Agent tool suspended without user input");
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
    public MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext) {
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

    private MangaWorkflowResult executeStreamedRequest(MangaWorkflowExecutionContext context,
                                                       MangaWorkflowStreamContext streamContext,
                                                       AgentRunRequest request) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            harnessAgentGateway.streamEvents(request)
                    .doOnNext(event -> {
                        captureWaitingState(context, event);
                        agentScopeEventMapper.map(event).ifPresent(mapped -> {
                            if (mangaAgentRunService.isTerminal(
                                    context.requestId(), context.user().getId(), context.chapter().getId())) {
                                throw new AgentRunTerminatedException();
                            }
                            if ("text_delta".equals(mapped.type()) && mapped.text() != null) {
                                reply.append(mapped.text());
                            }
                            streamContext.sink().sendRunEvent(streamContext.run(), mapped);
                        });
                    })
                    .blockLast(agentRunTimeout());
            finished.set(true);
            throwIfWaitingForUser(context, null);
        } catch (AgentRunTerminatedException e) {
            return MangaWorkflowResult.success("");
        } catch (AgentUserInputRequiredException e) {
            throw e;
        } catch (ToolSuspendException e) {
            throwIfWaitingForUser(context, null);
            throw new BusinessException(502, "Agent tool suspended without user input");
        } catch (Exception e) {
            if (mangaAgentRunService.isTerminal(context.requestId(), context.user().getId(), context.chapter().getId())) {
                return MangaWorkflowResult.success("");
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
            return MangaWorkflowResult.success("");
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
        return MangaWorkflowResult.success(finalReply);
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
                context.conversation().getConversationUuid(),
                activeToolGroups()
        );
    }

    private void syncWorkspace(MangaWorkflowExecutionContext context) {
        agentWorkspaceSyncService.syncMangaDirectorKnowledge(
                context.chapter().getId(),
                String.valueOf(context.user().getId())
        );
    }

    private void captureWaitingState(MangaWorkflowExecutionContext context, io.agentscope.core.event.AgentEvent event) {
        if (event instanceof io.agentscope.core.event.AgentResultEvent resultEvent) {
            throwIfWaitingForUser(context, resultEvent.getResult());
        }
    }

    private void throwIfWaitingForUser(MangaWorkflowExecutionContext context, Msg result) {
        if (result != null && result.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            return;
        }
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

    private static class AgentRunTerminatedException extends RuntimeException {
    }
}
