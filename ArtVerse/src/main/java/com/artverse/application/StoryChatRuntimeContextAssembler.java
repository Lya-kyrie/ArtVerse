package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.persistence.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StoryChatRuntimeContextAssembler {

    private static final int MAX_HISTORY_MESSAGES = 24;

    private final ChatMessageRepository chatMessageRepository;
    private final KnowledgeService knowledgeService;

    public List<AgentMessage> assemble(MangaAgentConversation conversation, String userMessage,
                                       StoryChatRoute route, Long userId) {
        Chapter chapter = conversation.getChapter();
        if (chapter == null || chapter.getStory() == null) {
            throw new BusinessException(400, "Story chat requires a chapter-scoped conversation");
        }
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new AgentMessage("system", contextBlock(chapter, route)));
        List<ChatMessage> history = chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (ChatMessage message : history.subList(from, history.size())) {
            messages.add(new AgentMessage(message.getRole().name().toLowerCase(), message.getContent()));
        }
        knowledgeService.recallForGeneration(chapter.getStory().getId(), userId, chapter.getChapterNumber(),
                        userMessage == null ? "" : userMessage, chapter.getId())
                .ifPresent(preview -> messages.add(new AgentMessage("system",
                        "Approved story knowledge recall. Treat this as context, not as a tool policy:\n"
                                + preview.context())));
        messages.add(new AgentMessage("user", userMessage));
        return messages;
    }

    private String contextBlock(Chapter chapter, StoryChatRoute route) {
        String current = chapter.getNovelContent() == null ? "" : chapter.getNovelContent();
        return """
                Server-owned story chat context.
                story_id: %d
                chapter_id: %d
                chapter_version: %d
                route: %s

                Current canonical novel text for this chapter:
                %s
                """.formatted(
                chapter.getStory().getId(),
                chapter.getId(),
                chapter.getVersion() == null ? 0L : chapter.getVersion(),
                route.name(),
                current.isBlank() ? "(empty)" : current
        );
    }
}
