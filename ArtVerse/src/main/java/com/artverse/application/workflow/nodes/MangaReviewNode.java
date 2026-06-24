package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import org.springframework.stereotype.Component;

@Component
public class MangaReviewNode extends AbstractStaticReplyNode {

    public MangaReviewNode(MangaAgentConversationService mangaAgentConversationService) {
        super(mangaAgentConversationService);
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.REVIEW;
    }

    @Override
    protected String responseText(MangaWorkflowExecutionContext context) {
        return """
                当前章节已有分镜内容，建议先检查是否需要修订。
                如果你要我直接改分镜，请说明修改方向；如果要继续生成，请切回导演流程。
                """.trim();
    }
}