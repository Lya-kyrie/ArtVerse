package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.ApiKeyService;
import com.artverse.application.NovelContentService;
import com.artverse.application.NovelContentProposalService;
import com.artverse.application.UserProviderConfig;
import com.artverse.application.tools.NovelContentTools;
import com.artverse.common.BusinessException;
import com.artverse.domain.ChapterNovelRevision;
import com.artverse.domain.NovelContentRevisionSource;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chapters/{chapterId}/novel-content")
@RequiredArgsConstructor
public class NovelContentController {
    private final NovelContentService novelContentService;
    private final NovelContentProposalService proposalService;
    private final NovelContentTools novelContentTools;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    @GetMapping("/revisions")
    public List<RevisionDto> revisions(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return novelContentService.listRevisions(chapterId, user.getId()).stream().map(RevisionDto::from).toList();
    }

    @PutMapping
    public Map<String, Object> save(@PathVariable Long chapterId, @RequestBody SaveRequest request) {
        User user = currentUserService.requireCurrentUser();
        return novelContentTools.saveNovelContent(chapterId, user.getId(), request.content(), request.baseVersion(),
                NovelContentRevisionSource.MANUAL);
    }

    @PostMapping("/ai-save")
    public Map<String, Object> saveAiProposal(@PathVariable Long chapterId, @RequestBody SaveRequest request) {
        throw new BusinessException(410, "Direct AI save has been retired. Create and commit a proposal instead.");
    }

    @PostMapping("/proposals")
    public NovelContentProposalService.ProposalResult createProposal(@PathVariable Long chapterId,
                                                                     @RequestBody CreateProposalRequest request) {
        throw new BusinessException(410, "Novel content proposals have been retired. Use story-chat AG-UI draft confirmation instead.");
    }

    @PutMapping("/proposals/{proposalId}")
    public NovelContentProposalService.ProposalResult updateProposal(@PathVariable Long chapterId,
                                                                     @PathVariable UUID proposalId,
                                                                     @RequestBody UpdateProposalRequest request) {
        throw new BusinessException(410, "Novel content proposals have been retired. Use story-chat AG-UI draft confirmation instead.");
    }

    @PostMapping("/proposals/{proposalId}/commit")
    public NovelContentProposalService.CommitResult commitProposal(@PathVariable Long chapterId,
                                                                   @PathVariable UUID proposalId,
                                                                   @RequestBody CommitProposalRequest request) {
        throw new BusinessException(410, "Novel content proposals have been retired. Use story-chat AG-UI draft confirmation instead.");
    }

    @PostMapping("/revisions/{revisionId}/restore")
    public Map<String, Object> restore(@PathVariable Long chapterId, @PathVariable Long revisionId,
                                       @RequestBody RestoreRequest request) {
        User user = currentUserService.requireCurrentUser();
        return novelContentTools.restoreNovelContent(chapterId, revisionId, user.getId(), request.baseVersion());
    }

    public record SaveRequest(String content, Long baseVersion, NovelContentRevisionSource source) {}
    public record CreateProposalRequest(UUID conversationId, Long throughMessageId, Long baseVersion,
                                        Long configId, String model) {}
    public record UpdateProposalRequest(String content, String expectedContentHash) {}
    public record CommitProposalRequest(Long baseVersion, String expectedContentHash) {}
    public record RestoreRequest(Long baseVersion) {}
    public record RevisionDto(Long id, Integer revisionNumber, String content, String contentHash, String source,
                              OffsetDateTime createdAt) {
        static RevisionDto from(ChapterNovelRevision value) {
            return new RevisionDto(value.getId(), value.getRevisionNumber(), value.getContent(), value.getContentHash(),
                    value.getSource().name().toLowerCase(), value.getCreatedAt());
        }
    }
}
