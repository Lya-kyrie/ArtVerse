package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ContentSource;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NovelService {

    private final ChapterRepository chapterRepository;
    private final ChatMessageRepository chatMessageRepository;
    @SuppressWarnings("unused")
    private final MangaImageRepository mangaImageRepository;
    private final AgentScopeHarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ArtVerseProperties properties;
    private final ArtVerseSkillRegistry skillRegistry;

    public String generateNovel(Long chapterId) {
        return generateNovel(chapterId, null, null);
    }

    public String generateNovel(Long chapterId, Long userId, String llmApiKey) {
        throw new BusinessException(410,
                "Direct AI novel generation has been retired. Create a novel-content proposal instead.");
    }

    public GeneratedNovelSnapshot generateNovelSnapshot(Long userId, Long storyId, Long chapterId,
                                                        String currentNovelContent, List<ChatMessage> messages,
                                                        UserProviderConfig llmConfig) {
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException(400, "No chat messages to generate novel from");
        }

        List<AgentMessage> agentMessages = new ArrayList<>();
        agentMessages.add(new AgentMessage("system", buildNovelSystemPrompt(currentNovelContent)));
        for (ChatMessage m : messages) {
            agentMessages.add(new AgentMessage(m.getRole().name().toLowerCase(), m.getContent()));
        }

        BusinessSkillSelection skillSelection = skillRegistry.selectionForNovelMode(
                NovelBusinessSkillMode.CHAPTER_WRITING);
        AgentRunRequest request = new AgentRunRequest(
                String.valueOf(userId),
                storyId,
                chapterId,
                AgentTaskType.NOVEL,
                agentMessages,
                Map.of("prompt_version", NovelContentProposalService.PROMPT_VERSION),
                agentModelSpecFactory.fromProviderConfig(llmConfig),
                llmConfig.apiKey(),
                UUID.randomUUID(),
                null,
                skillSelection
        );

        try {
            String content = harnessAgentGateway.generateText(request).block();
            return new GeneratedNovelSnapshot(content, skillSelection);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI service is unavailable: " + e.getMessage());
        }
    }

    @Transactional
    public Chapter importNovel(Long chapterId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "Content cannot be empty");
        }
        if (content.length() > properties.getImportConfig().getMaxNovelChars()) {
            throw new BusinessException(400,
                    "Content exceeds max length of " + properties.getImportConfig().getMaxNovelChars());
        }

        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        chapter.setNovelContent(content);
        chapter.setContentSource(ContentSource.IMPORT);
        return chapterRepository.save(chapter);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> legacyMessages(Long chapterId) {
        return chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(chapterId);
    }

    private String buildNovelSystemPrompt(String currentNovelContent) {
        String current = currentNovelContent == null || currentNovelContent.isBlank()
                ? "(empty)"
                : currentNovelContent.trim();
        return """
                You are a professional Chinese web-novel writer. Convert the current chapter text and the bounded story-chat conversation into one complete replacement snapshot of the chapter.

                Current canonical chapter text:
                %s

                Requirements:
                - Output the full replacement chapter, not a summary, explanation, continuation note, or chat reply.
                - Preserve useful established facts from the current text and conversation.
                - Use Chinese web-novel prose with concrete scene, dialogue, action, emotion, and pacing.
                - Do not include metadata, word counts, markdown headings, or explanations.
                """.formatted(current);
    }

    public record GeneratedNovelSnapshot(String content, BusinessSkillSelection businessSkillSelection) {
    }
}
