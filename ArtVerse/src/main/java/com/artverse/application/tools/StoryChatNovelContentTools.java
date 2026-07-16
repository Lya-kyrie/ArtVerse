package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.NovelContentArtifactService;
import com.artverse.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StoryChatNovelContentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService auditService;
    private final AgentRunToolStatus toolStatus;
    private final NovelContentArtifactService artifactService;

    @Tool(name = "draft_novel_content", description = "Persist a complete chapter novel text draft as an artifact. It does not change the chapter.", concurrencySafe = false)
    public Map<String, Object> draftNovelContent(String content, RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return auditService.around("draft_novel_content", context.userId(), context.chapterId(), runtimeContext, () -> {
            NovelContentArtifactService.ArtifactView artifact = artifactService.draft(content, context);
            Map<String, Object> payload = new LinkedHashMap<>(artifact.payload());
            payload.put("artifact_id", artifact.artifactId());
            payload.put("artifact_type", artifact.type());
            payload.put("status", artifact.status());
            payload.put("checksum", artifact.checksum());
            return payload;
        });
    }

    @Tool(name = "commit_novel_content", description = "Commit one user-confirmed novel content artifact as a new chapter revision. Only use after the user confirms the exact artifact.", concurrencySafe = false)
    public Map<String, Object> commitNovelContent(UUID artifactId, RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return auditService.around("commit_novel_content", context.userId(), context.chapterId(), runtimeContext, () -> {
            if (!toolStatus.isMutationArtifactAuthorized(context.userId(), context.chapterId(),
                    context.requestId(), artifactId)) {
                throw new BusinessException(409, "Novel content commit requires user confirmation for this artifact");
            }
            NovelContentArtifactService.CommitView result = artifactService.commit(artifactId, context, artifactId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("artifact_id", result.artifactId());
            response.put("changed", result.changed());
            response.put("chapter_version", result.chapterVersion());
            if (result.revisionId() != null) response.put("revision_id", result.revisionId());
            if (result.revisionNumber() != null) response.put("revision_number", result.revisionNumber());
            response.put("content_hash", result.contentHash());
            response.put("status", result.status());
            return response;
        });
    }
}
