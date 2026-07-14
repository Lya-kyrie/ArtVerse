package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NovelService {

    private final ChapterRepository chapterRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MangaImageRepository mangaImageRepository;
    private final AgentScopeHarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ArtVerseProperties properties;
    private final AgentOutboxService outboxService;

    @Autowired
    public NovelService(ChapterRepository chapterRepository,
                        ChatMessageRepository chatMessageRepository,
                        MangaImageRepository mangaImageRepository,
                        AgentScopeHarnessAgentGateway harnessAgentGateway,
                        AgentModelSpecFactory agentModelSpecFactory,
                        ArtVerseProperties properties,
                        AgentOutboxService outboxService) {
        this.chapterRepository = chapterRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.mangaImageRepository = mangaImageRepository;
        this.harnessAgentGateway = harnessAgentGateway;
        this.agentModelSpecFactory = agentModelSpecFactory;
        this.properties = properties;
        this.outboxService = outboxService;
    }

    public NovelService(ChapterRepository chapterRepository,
                        ChatMessageRepository chatMessageRepository,
                        MangaImageRepository mangaImageRepository,
                        AgentScopeHarnessAgentGateway harnessAgentGateway,
                        AgentModelSpecFactory agentModelSpecFactory,
                        ArtVerseProperties properties) {
        this(chapterRepository, chatMessageRepository, mangaImageRepository,
                harnessAgentGateway, agentModelSpecFactory, properties, null);
    }

    @Transactional
    public String generateNovel(Long chapterId) {
        return generateNovel(chapterId, null, null);
    }

    @Transactional
    public String generateNovel(Long chapterId, Long userId, String llmApiKey) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        List<ChatMessage> messages = chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(chapterId);
        if (messages.isEmpty()) {
            throw new BusinessException(400, "No chat messages to generate novel from");
        }

        List<AgentMessage> agentMessages = new ArrayList<>();
        agentMessages.add(new AgentMessage("system", buildNovelSystemPrompt()));
        for (ChatMessage m : messages) {
            agentMessages.add(new AgentMessage(m.getRole().name().toLowerCase(), m.getContent()));
        }

        UUID requestId = UUID.randomUUID();
        AgentRunRequest request = new AgentRunRequest(
                userId == null ? "default" : String.valueOf(userId),
                chapter.getStory().getId(),
                chapterId,
                AgentTaskType.NOVEL,
                agentMessages,
                Map.of(),
                agentModelSpecFactory.defaultLlm(llmApiKey),
                llmApiKey,
                requestId,
                null
        );

        String novelContent;
        try {
            novelContent = harnessAgentGateway.generateText(request).block();
        } catch (Exception e) {
            throw new BusinessException(502, "AI 服务不可用: " + e.getMessage());
        }
        if (novelContent == null || novelContent.isBlank()) {
            throw new BusinessException(502, "AI returned empty novel content");
        }

        chapter.setNovelContent(novelContent);
        chapterRepository.save(chapter);
        enqueueContentChanged(chapter, "GENERATED");

        return novelContent;
    }

    @Transactional
    public Chapter importNovel(Long chapterId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "Content cannot be empty");
        }
        if (content.length() > properties.getImportConfig().getMaxNovelChars()) {
            throw new BusinessException(400, "Content exceeds max length of " + properties.getImportConfig().getMaxNovelChars());
        }

        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        // Clear existing scenes
        chapter.setScenesText(null);

        // Delete existing chat messages to replace with imported content
        chatMessageRepository.deleteByChapterId(chapterId);

        // Save imported content as user message
        ChatMessage userMsg = new ChatMessage();
        userMsg.setChapter(chapter);
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(content);
        chatMessageRepository.save(userMsg);

        chapter.setNovelContent(content);
        chapter.setContentSource(ContentSource.IMPORT);
        Chapter saved = chapterRepository.save(chapter);
        enqueueContentChanged(saved, "IMPORTED");
        return saved;
    }

    private String buildNovelSystemPrompt() {
        return """
                你是一位专业的中文网络小说作家。请根据用户的对话内容，整理成完整的章节小说正文。

                要求：
                - 中文网络小说风格
                - 目标 4000-6000 中文字，不低于 3500 字
                - 包含 3-5 个完整场景
                - 强化环境描写、对话、心理活动、微表情、肢体动作
                - 直接输出正文，不输出解释或字数统计
                """;
    }

    private void enqueueContentChanged(Chapter chapter, String source) {
        if (outboxService == null) return;
        outboxService.enqueue("CHAPTER", String.valueOf(chapter.getId()),
                "CHAPTER_CONTENT_CHANGED", Map.of(
                        "user_id", chapter.getStory().getUser().getId(),
                        "story_id", chapter.getStory().getId(),
                        "chapter_id", chapter.getId(),
                        "chapter_number", chapter.getChapterNumber(),
                        "source", source));
    }
}
