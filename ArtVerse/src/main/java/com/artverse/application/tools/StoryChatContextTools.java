package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentToolAuditService;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.persistence.ChapterRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class StoryChatContextTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService auditService;
    private final ChapterRepository chapterRepository;

    @Tool(name = "get_novel_context", description = "Read the current story, chapter, canonical novel text and version. This tool never writes.", readOnly = true)
    public Map<String, Object> getNovelContext(RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = support.resolveContext(runtimeContext);
        return auditService.around("get_novel_context", context.userId(), context.chapterId(), runtimeContext, () -> {
            Chapter chapter = chapterRepository.findById(context.chapterId())
                    .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
            return Map.of(
                    "story_id", chapter.getStory().getId(),
                    "chapter_id", chapter.getId(),
                    "chapter_number", chapter.getChapterNumber() == null ? 0 : chapter.getChapterNumber(),
                    "chapter_version", chapter.getVersion() == null ? 0L : chapter.getVersion(),
                    "novel_content", chapter.getNovelContent() == null ? "" : chapter.getNovelContent()
            );
        });
    }
}
