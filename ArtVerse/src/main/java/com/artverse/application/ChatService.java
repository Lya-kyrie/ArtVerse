package com.artverse.application;

import com.artverse.agents.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.sse.SseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChapterRepository chapterRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final HarnessAgentGateway harnessAgentGateway;
    private final RuntimeContextFactory runtimeContextFactory;
    private final ArtVerseProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveUserMessage(Long chapterId, String content) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        ChatMessage msg = new ChatMessage();
        msg.setChapter(chapter);
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        chatMessageRepository.save(msg);

        chapter.setContentSource(ContentSource.CHAT);
        chapterRepository.save(chapter);
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

    public SseEmitter streamChat(Long chapterId, String userContent, String deepSeekApiKey) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        // Build context from all chapters up to current
        List<Chapter> chapters = chapterRepository.findByStoryIdUpToChapter(
                chapter.getStory().getId(), chapter.getChapterNumber());

        List<AgentMessage> contextMessages = new ArrayList<>();
        for (Chapter ch : chapters) {
            List<ChatMessage> msgs = chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(ch.getId());
            for (ChatMessage m : msgs) {
                contextMessages.add(new AgentMessage(m.getRole().name().toLowerCase(), m.getContent()));
            }
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        StringBuilder accumulated = new StringBuilder();

        AgentRunRequest request = new AgentRunRequest(
                "default",
                chapter.getStory().getId(),
                chapterId,
                AgentTaskType.CHAT,
                contextMessages,
                Map.of()
        );

        harnessAgentGateway.streamChat(request)
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
                                // Delete user message on error
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
                                // Save assistant message
                                String content = accumulated.toString();
                                ChatMessage assistantMsg = new ChatMessage();
                                assistantMsg.setChapter(chapter);
                                assistantMsg.setRole(MessageRole.ASSISTANT);
                                assistantMsg.setContent(content);
                                chatMessageRepository.save(assistantMsg);

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(objectMapper.writeValueAsString(Map.of("content", content))));
                                emitter.complete();
                            } catch (Exception e) {
                                log.warn("Failed to send done SSE: {}", e.getMessage());
                                emitter.complete();
                            }
                        }
                );

        emitter.onTimeout(() -> {
            String content = accumulated.toString();
            if (!content.isBlank()) {
                try {
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setChapter(chapter);
                    assistantMsg.setRole(MessageRole.ASSISTANT);
                    assistantMsg.setContent(content + "\n\n[已中止]");
                    chatMessageRepository.save(assistantMsg);
                } catch (Exception e) {
                    log.warn("Failed to save partial message on timeout: {}", e.getMessage());
                }
            }
        });

        emitter.onError(e -> {
            String content = accumulated.toString();
            if (!content.isBlank()) {
                try {
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setChapter(chapter);
                    assistantMsg.setRole(MessageRole.ASSISTANT);
                    assistantMsg.setContent(content + "\n\n[已中止]");
                    chatMessageRepository.save(assistantMsg);
                } catch (Exception ex) {
                    log.warn("Failed to save partial message on error: {}", ex.getMessage());
                }
            }
        });

        return emitter;
    }
}
