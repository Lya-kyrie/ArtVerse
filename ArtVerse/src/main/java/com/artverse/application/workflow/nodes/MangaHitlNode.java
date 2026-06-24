package com.artverse.application.workflow.nodes;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.workflow.MangaWorkflowExecutionContext;
import com.artverse.application.workflow.MangaWorkflowRoute;
import org.springframework.stereotype.Component;

@Component
public class MangaHitlNode extends AbstractStaticReplyNode {

    public MangaHitlNode(MangaAgentConversationService mangaAgentConversationService) {
        super(mangaAgentConversationService);
    }

    @Override
    public MangaWorkflowRoute route() {
        return MangaWorkflowRoute.HITL;
    }

    @Override
    protected String responseText(MangaWorkflowExecutionContext context) {
        return """
                这里需要你先做一个选择。
                请选择继续生成、修订分镜，或者切回导演流程后再继续。
                """.trim();
    }
}