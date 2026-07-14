package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.CharacterProfile;
import com.artverse.domain.Chapter;
import com.artverse.domain.KnowledgeCandidate;
import com.artverse.domain.KnowledgeCandidateStatus;
import com.artverse.domain.KnowledgeUnitType;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.KnowledgeCandidateRepository;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.persistence.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeCandidateService {

    private final KnowledgeCandidateRepository candidateRepository;
    private final ChapterRepository chapterRepository;
    private final StoryRepository storyRepository;
    private final UserRepository userRepository;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public record CandidateView(
            Long id,
            String sourceType,
            String sourceId,
            String knowledgeType,
            String title,
            String body,
            String summary,
            Map<String, Object> structuredData,
            Integer importance,
            Integer effectiveFromChapter,
            Integer effectiveToChapter,
            String status,
            Long approvedKnowledgeUnitId,
            String rejectionReason,
            OffsetDateTime createdAt,
            OffsetDateTime reviewedAt
    ) {
    }

    @Transactional(readOnly = true)
    public List<CandidateView> list(Long storyId, Long userId, String status) {
        requireStory(storyId, userId);
        List<KnowledgeCandidate> candidates = status == null || status.isBlank()
                ? candidateRepository.findByStoryIdAndUserIdOrderByCreatedAtDesc(storyId, userId)
                : candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                        storyId, userId, parseStatus(status));
        return candidates.stream().map(this::toView).toList();
    }

    @Transactional
    public CandidateView proposeCharacterProfile(CharacterProfile profile, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        Story story = profile.getStory();
        Map<String, Object> structured = Map.of(
                "aliases", List.of(),
                "identity", "",
                "personality", "",
                "abilities", "",
                "motivation", "",
                "taboos", "",
                "status", ""
        );
        String body = profile.getDescription() == null ? "" : profile.getDescription();
        return propose(user, story, "CHARACTER_PROFILE", String.valueOf(profile.getId()),
                KnowledgeUnitType.CHARACTER_CARD, profile.getName(), body,
                body.length() <= 300 ? body : body.substring(0, 300), structured, 5, null, null);
    }

    @Transactional
    public List<CandidateView> replaceChapterExtraction(
            Long chapterId,
            Long userId,
            List<ExtractedCandidate> extractedCandidates) {
        Chapter chapter = chapterRepository.findByIdForIdempotencyAndUserId(chapterId, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        Story story = chapter.getStory();
        User user = story.getUser();
        String sourcePrefix = chapterId + ":";
        candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                        story.getId(), userId, KnowledgeCandidateStatus.PENDING)
                .stream()
                .filter(candidate -> "CHAPTER_EXTRACTION".equals(candidate.getSourceType()))
                .filter(candidate -> candidate.getSourceId() != null
                        && candidate.getSourceId().startsWith(sourcePrefix))
                .forEach(candidate -> candidate.setStatus(KnowledgeCandidateStatus.SUPERSEDED));

        List<ExtractedCandidate> safeCandidates = extractedCandidates == null
                ? List.of()
                : extractedCandidates.stream().limit(20).toList();
        java.util.ArrayList<CandidateView> results = new java.util.ArrayList<>();
        for (int index = 0; index < safeCandidates.size(); index++) {
            ExtractedCandidate input = safeCandidates.get(index);
            if (input == null || input.title() == null || input.title().isBlank()
                    || input.body() == null || input.body().isBlank()) {
                continue;
            }
            KnowledgeUnitType type = parseKnowledgeType(input.knowledgeType());
            String body = clip(input.body().trim(), 12_000);
            String summary = input.summary() == null || input.summary().isBlank()
                    ? clip(body, 300)
                    : clip(input.summary().trim(), 500);
            CandidateView created = propose(user, story, "CHAPTER_EXTRACTION",
                    sourcePrefix + index, type, clip(input.title().trim(), 255), body, summary,
                    input.structuredData() == null ? Map.of() : input.structuredData(),
                    Math.max(1, Math.min(5, input.importance() == null ? 3 : input.importance())),
                    input.effectiveFromChapter() == null
                            ? chapter.getChapterNumber()
                            : input.effectiveFromChapter(),
                    input.effectiveToChapter());
            candidateRepository.findById(created.id()).ifPresent(candidate -> candidate.setChapter(chapter));
            results.add(created);
        }
        return List.copyOf(results);
    }

    @Transactional
    public CandidateView approve(Long storyId, Long candidateId, Long userId) {
        KnowledgeCandidate candidate = requirePending(storyId, candidateId, userId);
        KnowledgeService.UnitView unit;
        if ("CHARACTER_PROFILE".equals(candidate.getSourceType())) {
            try {
                unit = knowledgeService.approveCharacterProfile(
                        Long.valueOf(candidate.getSourceId()), userId);
            } catch (NumberFormatException error) {
                throw new BusinessException(409, "Candidate source is no longer valid");
            }
        } else {
            unit = knowledgeService.create(storyId, userId, new KnowledgeService.UnitInput(
                    candidate.getKnowledgeType().name(), candidate.getTitle(), candidate.getBody(),
                    candidate.getSummary(), readMap(candidate.getStructuredData()), candidate.getImportance(),
                    candidate.getEffectiveFromChapter(), candidate.getEffectiveToChapter()));
        }
        candidate.setApprovedKnowledgeUnitId(unit.id());
        candidate.setStatus(KnowledgeCandidateStatus.APPROVED);
        candidate.setReviewedBy(userId);
        candidate.setReviewedAt(OffsetDateTime.now());
        candidate.setRejectionReason(null);
        publishDecision(candidate, "KNOWLEDGE_CANDIDATE_APPROVED");
        return toView(candidateRepository.save(candidate));
    }

    @Transactional
    public CandidateView reject(Long storyId, Long candidateId, Long userId, String reason) {
        KnowledgeCandidate candidate = requirePending(storyId, candidateId, userId);
        candidate.setStatus(KnowledgeCandidateStatus.REJECTED);
        candidate.setReviewedBy(userId);
        candidate.setReviewedAt(OffsetDateTime.now());
        candidate.setRejectionReason(reason == null || reason.isBlank() ? null : clip(reason, 500));
        publishDecision(candidate, "KNOWLEDGE_CANDIDATE_REJECTED");
        return toView(candidateRepository.save(candidate));
    }

    private CandidateView propose(User user, Story story, String sourceType, String sourceId,
                                  KnowledgeUnitType type, String title, String body, String summary,
                                  Map<String, Object> structuredData, int importance,
                                  Integer effectiveFrom, Integer effectiveTo) {
        String structured = writeJson(structuredData);
        String proposedHash = hash(sourceType, sourceId, type.name(), title, body, summary, structured,
                String.valueOf(effectiveFrom), String.valueOf(effectiveTo));
        return candidateRepository.findFirstByStoryIdAndProposedHashAndStatus(
                        story.getId(), proposedHash, KnowledgeCandidateStatus.PENDING)
                .map(this::toView)
                .orElseGet(() -> {
                    candidateRepository.findByStoryIdAndUserIdAndStatusOrderByCreatedAtDesc(
                                    story.getId(), user.getId(), KnowledgeCandidateStatus.PENDING)
                            .stream()
                            .filter(existing -> sourceType.equals(existing.getSourceType())
                                    && sourceId.equals(existing.getSourceId()))
                            .forEach(existing -> existing.setStatus(KnowledgeCandidateStatus.SUPERSEDED));
                    KnowledgeCandidate candidate = new KnowledgeCandidate();
                    candidate.setUser(user);
                    candidate.setStory(story);
                    candidate.setSourceType(sourceType);
                    candidate.setSourceId(sourceId);
                    candidate.setKnowledgeType(type);
                    candidate.setTitle(title == null ? "" : title);
                    candidate.setBody(body == null ? "" : body);
                    candidate.setSummary(summary == null ? "" : summary);
                    candidate.setStructuredData(structured);
                    candidate.setImportance(importance);
                    candidate.setEffectiveFromChapter(effectiveFrom);
                    candidate.setEffectiveToChapter(effectiveTo);
                    candidate.setProposedHash(proposedHash);
                    KnowledgeCandidate saved = candidateRepository.save(candidate);
                    publishDecision(saved, "KNOWLEDGE_CANDIDATE_CREATED");
                    return toView(saved);
                });
    }

    private KnowledgeCandidate requirePending(Long storyId, Long candidateId, Long userId) {
        requireStory(storyId, userId);
        KnowledgeCandidate candidate = candidateRepository.findByIdAndStoryIdAndUserId(
                        candidateId, storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Knowledge candidate not found"));
        if (candidate.getStatus() != KnowledgeCandidateStatus.PENDING) {
            throw new BusinessException(409, "Knowledge candidate is already decided");
        }
        return candidate;
    }

    private Story requireStory(Long storyId, Long userId) {
        return storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
    }

    private void publishDecision(KnowledgeCandidate candidate, String eventType) {
        jdbcTemplate.update("""
                INSERT INTO agent_outbox_events(aggregate_type, aggregate_id, event_type, payload)
                VALUES ('KNOWLEDGE_CANDIDATE', ?, ?, CAST(? AS jsonb))
                """, String.valueOf(candidate.getId()), eventType, writeJson(Map.of(
                "candidate_id", candidate.getId(),
                "story_id", candidate.getStory().getId(),
                "status", candidate.getStatus().name())));
    }

    private CandidateView toView(KnowledgeCandidate candidate) {
        return new CandidateView(candidate.getId(), candidate.getSourceType(), candidate.getSourceId(),
                candidate.getKnowledgeType().name(), candidate.getTitle(), candidate.getBody(),
                candidate.getSummary(), readMap(candidate.getStructuredData()), candidate.getImportance(),
                candidate.getEffectiveFromChapter(), candidate.getEffectiveToChapter(),
                candidate.getStatus().name(), candidate.getApprovedKnowledgeUnitId(),
                candidate.getRejectionReason(), candidate.getCreatedAt(), candidate.getReviewedAt());
    }

    private KnowledgeCandidateStatus parseStatus(String status) {
        try {
            return KnowledgeCandidateStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid knowledge candidate status");
        }
    }

    private KnowledgeUnitType parseKnowledgeType(String type) {
        try {
            return KnowledgeUnitType.valueOf(type == null ? "" : type.trim().toUpperCase());
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid extracted knowledge type");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception error) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid knowledge candidate data");
        }
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder output = new StringBuilder();
            for (byte value : digest.digest()) output.append(String.format("%02x", value));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record ExtractedCandidate(
            String knowledgeType,
            String title,
            String body,
            String summary,
            Map<String, Object> structuredData,
            Integer importance,
            Integer effectiveFromChapter,
            Integer effectiveToChapter
    ) {
    }
}
