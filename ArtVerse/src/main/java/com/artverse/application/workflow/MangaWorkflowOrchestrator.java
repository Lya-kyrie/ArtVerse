package com.artverse.application.workflow;

import com.artverse.agents.AgentModelSpec;
import com.artverse.agents.AgentModelSpecFactory;
import com.artverse.agents.AgentRunEvent;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.MangaAgentRunService;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaWorkflowOrchestrator {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaWorkflowContextAssembler mangaWorkflowContextAssembler;
    private final MangaWorkflowNodeRegistry nodeRegistry;

    public MangaWorkflowResult runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState, MangaWorkflowRoute route) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }
        var cached = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cached.isPresent()) {
            return MangaWorkflowResult.success(cached.get().getContent());
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        String deepseekApiKey = requireDeepseekApiKey(user);
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);
        MangaWorkflowRoute effectiveRoute = route == null ? MangaWorkflowRoute.DIRECTOR : route;
        return generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapter.getStory().getId(),
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                effectiveRoute.name(),
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState, effectiveRoute)
        );
    }

    public MangaWorkflowResult runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, toolState, MangaWorkflowRoute.DIRECTOR);
    }

    public MangaWorkflowResult runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId, String deepseekApiKey,
                                                 AgentModelSpec modelSpec, AgentRunToolStatus.RunState toolState,
                                                 MangaWorkflowRoute route) {
        MangaWorkflowContextSnapshot workflowContext = mangaWorkflowContextAssembler.assemble(conversation, message, route);
        log.info("Workflow route for request {} -> {}", effectiveRequestId, workflowContext.route());
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState, workflowContext);
        return nodeRegistry.handlerFor(workflowContext.route()).run(context);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef, MangaWorkflowRoute route) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();
        MangaWorkflowRoute effectiveRoute = route == null ? MangaWorkflowRoute.DIRECTOR : route;
        MangaAgentRun run = mangaAgentRunService.startOrReuse(conversation, effectiveRequestId, message, effectiveRoute);
        runRef.set(run);
        sink.sendStatus(run, "智能体开始处理当前章节", effectiveRequestId);

        if (mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId).isPresent()) {
            MangaWorkflowResult result = runWithToolState(conversation, message, effectiveRequestId, toolState, effectiveRoute);
            mangaAgentRunService.markSucceeded(conversation, effectiveRequestId, result.reply());
            sink.sendDone(run, result.reply(), effectiveRequestId);
            return;
        }

        String deepseekApiKey = requireDeepseekApiKey(user);
        AgentModelSpec modelSpec = agentModelSpecFactory.deepSeek(deepseekApiKey);
        MangaWorkflowResult result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapterId,
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                effectiveRoute.name(),
                () -> runWorkflowStream(conversation, message, effectiveRequestId, sink, toolState,
                        deepseekApiKey, modelSpec, run, effectiveRoute)
        );

        completeRun(run, sink, chapterId, user, effectiveRequestId, result);
    }

    public MangaWorkflowResult runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String deepseekApiKey, AgentModelSpec modelSpec,
                                                 MangaAgentRun run,
                                                 MangaWorkflowRoute route) {
        MangaWorkflowContextSnapshot workflowContext = mangaWorkflowContextAssembler.assemble(conversation, message, route);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, deepseekApiKey, modelSpec, toolState, workflowContext);
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.ROUTING.name(),
                "running",
                "正在路由当前任务",
                Map.of("route", workflowContext.route().name())
        ));
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.COLLECTING_CONTEXT.name(),
                "running",
                "正在收集上下文信息",
                Map.of(
                        "storyTitle", workflowContext.storyTitle(),
                        "chapterDisplayName", workflowContext.chapterDisplayName(),
                        "sceneCount", workflowContext.sceneCount(),
                        "imageCount", workflowContext.imageCount(),
                        "warnings", workflowContext.warnings()
                )
        ));
        MangaWorkflowResult response = nodeRegistry.handlerFor(workflowContext.route())
                .stream(context, new MangaWorkflowStreamContext(run, sink));
        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "正在评估生成结果",
                Map.of("degraded", response.degraded())
        ));
        return response;
    }

    public void completeRun(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink, Long chapterId, User user,
                            UUID requestId, MangaWorkflowResult result) {
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapterId)) {
            sink.complete();
            return;
        }
        if (result.degraded()) {
            mangaAgentRunService.markDegraded(run.getConversation(), requestId, result.reply(),
                    "Agent final response degraded after tool success");
        } else {
            mangaAgentRunService.markSucceeded(run.getConversation(), requestId, result.reply());
        }
        sink.sendDone(run, result.reply(), requestId);
    }

    public String requireDeepseekApiKey(User user) {
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }
        return deepseekApiKey;
    }

    private MangaWorkflowExecutionContext executionContext(MangaAgentConversation conversation, String message,
                                                           UUID effectiveRequestId, String deepseekApiKey,
                                                           AgentModelSpec modelSpec,
                                                           AgentRunToolStatus.RunState toolState,
                                                           MangaWorkflowContextSnapshot workflowContext) {
        return new MangaWorkflowExecutionContext(
                conversation,
                message,
                effectiveRequestId,
                deepseekApiKey,
                modelSpec,
                toolState,
                conversation.getUser(),
                conversation.getChapter(),
                workflowContext
        );
    }
}
