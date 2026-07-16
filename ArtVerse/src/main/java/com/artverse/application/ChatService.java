package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ChatMessageCompletionStatus;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChapterRepository chapterRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentScopeHarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;
    private final AiConversationService aiConversationService;
    private final ArtVerseSkillRegistry skillRegistry;
    private final NovelBusinessSkillRouter novelBusinessSkillRouter;

    @Transactional
    public void saveUserMessage(Long chapterId, String content, User user) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        ChatMessage msg = new ChatMessage();
        msg.setChapter(chapter);
        MangaAgentConversation conversation = aiConversationService.storyConversation(user, chapterId);
        msg.setConversation(conversation);
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        msg.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
        chatMessageRepository.save(msg);
        aiConversationService.autoTitle(conversation, content);
        aiConversationService.touch(conversation);

        // Conversation is independent from the canonical chapter text. Do not
        // overwrite legacy provenance or make future source editing impossible.
    }

    @Transactional
    public void deleteLastUserMessage(Long chapterId) {
        List<ChatMessage> messages = chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(chapterId);
        if (!messages.isEmpty()) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.getRole() == MessageRole.USER) {
                chatMessageRepository.delete(last);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(Long chapterId) {
        return chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(chapterId);
    }

    public SseEmitter streamChat(Long chapterId, User user, UserProviderConfig llmConfig) {
        Long userId = user.getId();
        StreamContext ctx = loadStreamContext(chapterId);
        Chapter chapter = ctx.chapter();
        MangaAgentConversation conversation = aiConversationService.storyConversation(user, chapterId);
        List<AgentMessage> contextMessages = new ArrayList<>(ctx.contextMessages());
        String recallQuery = contextMessages.stream().map(AgentMessage::content).reduce("", (left, right) -> left + "\n" + right);
        knowledgeService.recallForGeneration(chapter.getStory().getId(), userId, chapter.getChapterNumber(), recallQuery, chapterId)
                .ifPresent(preview -> contextMessages.add(new AgentMessage("system", preview.context())));

        SseEmitter emitter = new SseEmitter(0L);
        StringBuilder accumulated = new StringBuilder();

        UUID requestId = UUID.randomUUID();
        UUID conversationId = conversationIdForChapter(chapterId);
        String latestUserMessage = latestUserMessage(contextMessages);
        BusinessSkillSelection skillSelection = skillRegistry.selectionForNovelMode(
                novelBusinessSkillRouter.classify(latestUserMessage).mode());
        AgentRunRequest request = new AgentRunRequest(
                String.valueOf(userId),
                chapter.getStory().getId(),
                chapterId,
                AgentTaskType.CHAT,
                contextMessages,
                Map.of(),
                agentModelSpecFactory.fromProviderConfig(llmConfig),
                llmConfig.apiKey(),
                requestId,
                conversationId,
                skillSelection
        );

        Disposable subscription = harnessAgentGateway.streamChat(request)
                .subscribe(
                        token -> {
                            try {
                                accumulated.append(token);
                                emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(objectMapper.writeValueAsString(Map.of("content", token))));
                            } catch (IOException e) {
                                log.warn("Failed to send token SSE: {}", e.getMessage());
                            }
                        },
                        error -> {
                            try {
                                deleteLastUserMessage(chapterId);
                                String detail = error.getMessage() != null ? error.getMessage() : "Unknown error";
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(objectMapper.writeValueAsString(Map.of("detail", detail))));
                            } catch (Exception e) {
                                log.warn("Failed to send error SSE: {}", e.getMessage());
                            }
                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                String content = accumulated.toString();
                                ChatMessage assistantMsg = new ChatMessage();
                                assistantMsg.setChapter(chapter);
                                assistantMsg.setConversation(conversation);
                                assistantMsg.setRole(MessageRole.ASSISTANT);
                                assistantMsg.setContent(content);
                                assistantMsg.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
                                assistantMsg.setSkillVersionsJson(writeSkillVersions(skillSelection));
                                assistantMsg = chatMessageRepository.save(assistantMsg);
                                aiConversationService.touch(conversation);

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(objectMapper.writeValueAsString(Map.of(
                                                "content", content,
                                                "message", messageSummary(assistantMsg),
                                                "conversation", conversationSummary(conversation)))));
                                emitter.complete();
                            } catch (Exception e) {
                                log.warn("Failed to send done SSE: {}", e.getMessage());
                                emitter.complete();
                            }
                        }
                );

        emitter.onCompletion(() -> {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
        });

        emitter.onTimeout(() -> {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
            String content = accumulated.toString();
            if (!content.isBlank()) {
                try {
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setChapter(chapter);
                    assistantMsg.setConversation(conversation);
                    assistantMsg.setRole(MessageRole.ASSISTANT);
                    assistantMsg.setContent(content + "\n\n[宸蹭腑姝");
                    assistantMsg.setCompletionStatus(ChatMessageCompletionStatus.PARTIAL);
                    assistantMsg.setSkillVersionsJson(writeSkillVersions(skillSelection));
                    chatMessageRepository.save(assistantMsg);
                } catch (Exception e) {
                    log.warn("Failed to save partial message on timeout: {}", e.getMessage());
                }
            }
        });

        emitter.onError(e -> {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
            String content = accumulated.toString();
            if (!content.isBlank()) {
                try {
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setChapter(chapter);
                    assistantMsg.setConversation(conversation);
                    assistantMsg.setRole(MessageRole.ASSISTANT);
                    assistantMsg.setContent(content + "\n\n[宸蹭腑姝");
                    assistantMsg.setCompletionStatus(ChatMessageCompletionStatus.PARTIAL);
                    assistantMsg.setSkillVersionsJson(writeSkillVersions(skillSelection));
                    chatMessageRepository.save(assistantMsg);
                } catch (Exception ex) {
                    log.warn("Failed to save partial message on error: {}", ex.getMessage());
                }
            }
        });

        return emitter;
    }

    private Map<String, Object> conversationSummary(MangaAgentConversation conversation) {
        return Map.of(
                "conversationId", conversation.getConversationUuid().toString(),
                "title", conversation.getTitle(),
                "titleSource", conversation.getTitleSource().name(),
                "titleState", conversation.getTitleState().name()
        );
    }

    private Map<String, Object> messageSummary(ChatMessage message) {
        return Map.of(
                "id", message.getId(),
                "role", message.getRole().name().toLowerCase(),
                "content", message.getContent(),
                "completion_status", message.getCompletionStatus().name().toLowerCase(),
                "created_at", message.getCreatedAt().toString()
        );
    }

    @Transactional(readOnly = true)
    private StreamContext loadStreamContext(Long chapterId) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        List<Chapter> chapters = chapterRepository.findByStoryIdUpToChapter(
                chapter.getStory().getId(), chapter.getChapterNumber());

        List<AgentMessage> contextMessages = new ArrayList<>();
        for (Chapter ch : chapters) {
            if (ch.getNovelContent() != null && !ch.getNovelContent().isBlank()) {
                contextMessages.add(new AgentMessage("system", "Canonical novel text for chapter "
                        + ch.getChapterNumber() + ":\n" + ch.getNovelContent()));
            }
            List<ChatMessage> msgs = chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(ch.getId());
            for (ChatMessage m : msgs) {
                if (m.getConversation() != null
                        && m.getConversation().getConversationType() != AiConversationType.STORY_CHAT) {
                    continue;
                }
                contextMessages.add(new AgentMessage(m.getRole().name().toLowerCase(), m.getContent()));
            }
        }

        return new StreamContext(chapter, contextMessages);
    }

    private String latestUserMessage(List<AgentMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            AgentMessage message = messages.get(index);
            if ("user".equalsIgnoreCase(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private String writeSkillVersions(BusinessSkillSelection selection) {
        try {
            return objectMapper.writeValueAsString(selection.skillVersions());
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize chat skill versions", error);
        }
    }

    private record StreamContext(Chapter chapter, List<AgentMessage> contextMessages) {
    }

    private static UUID conversationIdForChapter(Long chapterId) {
        return new UUID(chapterId, chapterId);
    }
}
