package com.artverse.application.workflow;

public interface MangaWorkflowNodeHandler {

    MangaWorkflowRoute route();

    MangaWorkflowResult run(MangaWorkflowExecutionContext context);

    MangaWorkflowResult stream(MangaWorkflowExecutionContext context, MangaWorkflowStreamContext streamContext);
}
