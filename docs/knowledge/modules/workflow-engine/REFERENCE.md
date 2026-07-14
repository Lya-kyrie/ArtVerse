# Workflow Engine - Call Graph

## Called By (inbound)

```
api/MangaAgentController  (SSE + sync endpoints)
  -> MangaAgentService.run() / runAgUiStream() / resume()
    -> MangaWorkflowOrchestrator.runWithToolState() / runStreamLeader()
      -> MangaWorkflowRouter.route()  (AgentScope structured classification + capability validation)
      -> MangaWorkflowContextAssembler.assemble()
      -> MangaWorkflowNodeRegistry.handlerFor(selectedRoute)
        -> Conversation / Creative / Storyboard / Review specialist
        -> MangaDirectorAgentNode for validated multi-step plans
          -> MangaAgentExecutionSupport
          -> AgentScopeHarnessAgentGateway
```

## Calls Into (outbound)

```
MangaWorkflowOrchestrator
  -> MangaAgentConversationService  (cached assistant reply lookup)
  -> MangaAgentRunService           (run lifecycle)
  -> AgentModelSpecFactory          (model spec creation)
  -> ApiKeyService                  (user LLM config)
  -> GenerationGuardService         (idempotency + rate limiting)
  -> MangaWorkflowContextAssembler  (context snapshots)
  -> MangaWorkflowNodeRegistry      (node dispatch)
  -> MangaWorkflowRouter            (automatic route decision)

MangaWorkflowContextAssembler
  -> MangaAgentConversationService  (conversation history)
  -> MangaImageRepository           (image list)
  -> CharacterProfileService        (effective character profile)

MangaDirectorAgentNode
  -> MangaAgentRunService           (routing decision and execution-plan recovery)
  -> MangaWorkflowNodeRegistry      (serial child-step dispatch)

MangaConversationAgentNode / MangaCreativeAgentNode / MangaStoryboardAgentNode / MangaReviewAgentNode
  -> MangaAgentExecutionSupport     (shared request construction and execution)

MangaAgentExecutionSupport
  -> MangaAgentConversationService  (message management)
  -> AgentWorkspaceSyncService      (knowledge sync)
  -> ApiKeyService                  (Coze key lookup for runtime metadata)

Agent Tools (MangaContextTools, MangaStoryboardTools, MangaHitlTools)
  -> ChapterAccessService           (visibility checks)
  -> SceneService                   (storyboard generation)
  -> StructuredStoryboardService    (structured storyboard)
  -> MangaImageRepository           (image data)
  -> GenerationGuardService         (concurrency guard)
  -> AgentToolAuditService          (audit logging)
  -> AgentRunToolStatus             (per-run state)
  -> ToolIdempotencyService         (atomic request-scoped write deduplication)
```

## Key Dependencies

| Downstream Module | Purpose |
|-------------------|---------|
| `agent-integration` | AgentScope SDK execution |
| `application-services` | Business logic (SceneService, CharacterProfileService, etc.) |
| `domain-model` | Entities (`MangaAgentRun`, conversations, chapters) |
| `data-access` | Repositories |
| `guard` | Idempotency, rate limiting, concurrency |
| `config` | `ArtVerseProperties` |
