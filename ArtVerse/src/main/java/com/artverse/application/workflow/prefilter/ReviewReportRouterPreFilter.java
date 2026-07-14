package com.artverse.application.workflow.prefilter;

import com.artverse.application.workflow.MangaWorkflowCapability;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ReviewReportRouterPreFilter implements RouterPreFilter {

    @Override
    public Optional<RoutingDecision> filter(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String normalized = message.strip().toLowerCase()
                .replaceAll("\\s+", "");
        if (asksForQualityReport(normalized) && mentionsCurrentChapterWork(normalized)) {
            return Optional.of(new RoutingDecision(
                    MangaWorkflowRoute.REVIEW,
                    1.0,
                    List.of("chapter_quality_report"),
                    false,
                    false,
                    "prefilter:chapter_quality_report",
                    List.of(MangaWorkflowRoute.REVIEW),
                    RoutingDecision.CURRENT_VERSION,
                    Set.of(MangaWorkflowCapability.STORYBOARD_READ, MangaWorkflowCapability.STORYBOARD_REVIEW)
            ));
        }
        return Optional.empty();
    }

    private boolean asksForQualityReport(String text) {
        return text.contains("分析")
                || text.contains("报告")
                || text.contains("质量")
                || text.contains("怎么样")
                || text.contains("如何")
                || text.contains("review")
                || text.contains("quality")
                || text.contains("report");
    }

    private boolean mentionsCurrentChapterWork(String text) {
        boolean currentChapter = text.contains("本章")
                || text.contains("当前章节")
                || text.contains("这一章")
                || text.contains("这章")
                || text.contains("chapter");
        boolean mangaOrNovel = text.contains("漫画")
                || text.contains("分镜")
                || text.contains("小说")
                || text.contains("正文")
                || text.contains("manga")
                || text.contains("storyboard")
                || text.contains("novel");
        return currentChapter && mangaOrNovel;
    }

    @Override
    public int getOrder() {
        return 300;
    }
}
