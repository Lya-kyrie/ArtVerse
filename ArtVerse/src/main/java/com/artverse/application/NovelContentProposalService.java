package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.AiConversationType;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChapterNovelRevision;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ChatMessageCompletionStatus;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MessageRole;
import com.artverse.domain.NovelContentProposal;
import com.artverse.domain.NovelContentProposalStatus;
import com.artverse.domain.NovelContentRevisionSource;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.NovelContentProposalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NovelContentProposalService {
    public static final String PROMPT_VERSION = "novel-content-proposal-v1";

    private final NovelContentProposalRepository proposalRepository;
    private final MangaAgentConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChapterNovelRevisionRepository revisionRepository;
    private final NovelContentService novelContentService;
    private final NovelService novelService;
    private final ArtVerseProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public ProposalResult create(User user, Long chapterId, UUID conversationId, Long throughMessageId,
                                 Long baseVersion, UserProviderConfig llmConfig) {
        if (baseVersion == null) throw new BusinessException(400, "base_version is required");
        if (conversationId == null) throw new BusinessException(400, "conversation_id is required");
        if (throughMessageId == null) throw new BusinessException(400, "through_message_id is required");

        MangaAgentConversation conversation = conversationRepository
                .findByUserIdAndChapterIdAndConversationUuidAndConversationType(
                        user.getId(), chapterId, conversationId, AiConversationType.STORY_CHAT)
                .orElseThrow(() -> new BusinessException(404, "Story chat conversation not found"));
        if (conversation.getStatus() != MangaAgentConversationStatus.ACTIVE) {
            throw new BusinessException(409, "Story chat conversation is not active");
        }
        Chapter chapter = conversation.getChapter();
        if (!baseVersion.equals(chapter.getVersion())) {
            throw new BusinessException(409, "The novel text changed. Refresh before creating a proposal.");
        }

        ChatMessage boundary = chatMessageRepository.findByIdAndConversationId(throughMessageId, conversation.getId())
                .orElseThrow(() -> new BusinessException(404, "through_message_id is not in this conversation"));
        if (boundary.getRole() != MessageRole.ASSISTANT) {
            throw new BusinessException(400, "through_message_id must point to an assistant message");
        }
        if (boundary.getCompletionStatus() != ChatMessageCompletionStatus.COMPLETE) {
            throw new BusinessException(400, "through_message_id must point to a complete assistant message");
        }

        List<ChatMessage> boundedMessages = chatMessageRepository
                .findByConversationIdAndIdLessThanEqualOrderByCreatedAtAscIdAsc(conversation.getId(), throughMessageId);
        NovelService.GeneratedNovelSnapshot snapshot = novelService.generateNovelSnapshot(
                user.getId(), chapter.getStory().getId(), chapterId, chapter.getNovelContent(), boundedMessages, llmConfig);
        String generated = normalizeCandidate(snapshot.content());
        String hash = ToolIdempotencyService.sha256(generated);

        NovelContentProposal proposal = new NovelContentProposal();
        proposal.setChapter(chapter);
        proposal.setConversation(conversation);
        proposal.setThroughMessage(boundary);
        proposal.setBaseVersion(baseVersion);
        proposal.setGeneratedContent(generated);
        proposal.setGeneratedContentHash(hash);
        proposal.setDraftContent(generated);
        proposal.setDraftContentHash(hash);
        proposal.setProviderConfigId(llmConfig.configId());
        proposal.setModel(llmConfig.model());
        proposal.setPromptVersion(PROMPT_VERSION);
        proposal.setSkillVersionsJson(toJson(snapshot.businessSkillSelection().skillVersions()));
        proposal.setStatus(NovelContentProposalStatus.DRAFT);
        proposal.setCreatedBy(user);
        proposal = proposalRepository.save(proposal);
        return ProposalResult.from(proposal);
    }

    @Transactional
    public ProposalResult updateDraft(User user, Long chapterId, UUID proposalId,
                                      String content, String expectedContentHash) {
        NovelContentProposal proposal = requireDraftProposal(user, chapterId, proposalId);
        requireHash(proposal.getDraftContentHash(), expectedContentHash);
        String normalized = normalizeCandidate(content);
        proposal.setDraftContent(normalized);
        proposal.setDraftContentHash(ToolIdempotencyService.sha256(normalized));
        return ProposalResult.from(proposal);
    }

    @Transactional
    public CommitResult commit(User user, Long chapterId, UUID proposalId,
                               Long baseVersion, String expectedContentHash) {
        if (baseVersion == null) throw new BusinessException(400, "base_version is required");
        NovelContentProposal proposal = proposalRepository.findLockedByIdAndChapterId(proposalId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "Novel content proposal not found"));
        if (!proposal.getChapter().getStory().getUser().getId().equals(user.getId())) {
            throw new BusinessException(404, "Novel content proposal not found");
        }
        requireHash(proposal.getDraftContentHash(), expectedContentHash);

        if (proposal.getStatus() == NovelContentProposalStatus.COMMITTED) {
            ChapterNovelRevision revision = revisionRepository.findByProposalId(proposal.getId()).orElse(null);
            return CommitResult.from(proposal, revision, false, proposal.getChapter().getVersion());
        }
        if (!baseVersion.equals(proposal.getBaseVersion())) {
            throw new BusinessException(409, "Proposal was created from a different chapter version.");
        }

        NovelContentService.SaveResult saved = novelContentService.save(
                chapterId, user.getId(), proposal.getDraftContent(), baseVersion,
                NovelContentRevisionSource.AI, proposal);
        proposal.setStatus(NovelContentProposalStatus.COMMITTED);
        proposal.setCommittedAt(OffsetDateTime.now());
        return CommitResult.from(proposal, saved.revision(), saved.changed(), saved.chapter().getVersion());
    }

    private NovelContentProposal requireDraftProposal(User user, Long chapterId, UUID proposalId) {
        NovelContentProposal proposal = proposalRepository.findLockedByIdAndChapterId(proposalId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "Novel content proposal not found"));
        if (!proposal.getChapter().getStory().getUser().getId().equals(user.getId())) {
            throw new BusinessException(404, "Novel content proposal not found");
        }
        if (proposal.getStatus() != NovelContentProposalStatus.DRAFT) {
            throw new BusinessException(409, "Novel content proposal is already committed");
        }
        return proposal;
    }

    private String normalizeCandidate(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(502, "AI returned empty novel content");
        }
        String normalized = content.trim();
        int max = properties.getImportConfig().getMaxNovelChars();
        if (normalized.length() > max) {
            throw new BusinessException(502, "AI returned novel content exceeding max length of " + max);
        }
        return normalized;
    }

    private void requireHash(String actual, String expected) {
        if (expected == null || expected.isBlank()) throw new BusinessException(400, "expected_content_hash is required");
        if (!expected.equals(actual)) throw new BusinessException(409, "Proposal content changed. Refresh before committing.");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize novel proposal skill versions", error);
        }
    }

    public record ProposalResult(UUID proposalId, String content, String contentHash, Long baseVersion,
                                 Long throughMessageId, String status) {
        static ProposalResult from(NovelContentProposal proposal) {
            return new ProposalResult(proposal.getId(), proposal.getDraftContent(), proposal.getDraftContentHash(),
                    proposal.getBaseVersion(), proposal.getThroughMessage().getId(),
                    proposal.getStatus().name().toLowerCase());
        }
    }

    public record CommitResult(UUID proposalId, boolean changed, Long chapterVersion, Long revisionId,
                               Integer revisionNumber, String contentHash, String status) {
        static CommitResult from(NovelContentProposal proposal, ChapterNovelRevision revision,
                                 boolean changed, Long chapterVersion) {
            return new CommitResult(proposal.getId(), changed, chapterVersion,
                    revision == null ? null : revision.getId(),
                    revision == null ? null : revision.getRevisionNumber(),
                    proposal.getDraftContentHash(),
                    proposal.getStatus().name().toLowerCase());
        }
    }
}
