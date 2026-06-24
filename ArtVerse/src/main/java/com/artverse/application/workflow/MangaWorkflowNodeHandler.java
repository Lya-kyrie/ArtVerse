package com.artverse.application.workflow;

import java.util.List;

public interface MangaWorkflowNodeHandler {

    MangaWorkflowRoute route();

    default List<String> activeToolGroups() {
        return List.of();
    }

    MangaWorkflowResult run(MangaWorkflowExecutionContext context);

    MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext);
}
