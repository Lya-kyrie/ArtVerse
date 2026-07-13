# Workflow Engine - Call Graph

## Called By (inbound)

```
api/MangaAgentController  (SSE + sync endpoints)
  -> MangaAgentService.run() / runAgUiStream() / resume()
    -> MangaWorkflowOrchestrator.runWithToolState() / runStreamLeader()
      -> MangaWorkflowContextAssembler.assemble()
      -> MangaWorkflowNodeRegistry.handlerFor(DIRECTOR)
        -> MangaDirectorAgentNode.run() / stream()
          -> MangaDirectorAgentSupport
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

MangaWorkflowContextAssembler
  -> MangaAgentConversationService  (conversation history)
  -> MangaImageRepository           (image list)
  -> CharacterProfileService        (effective character profile)

MangaDirectorAgentNode
  -> AgentScopeHarnessAgentGateway  (LLM communication)
  -> MangaDirectorAgentSupport      (request build, workspace sync, event mapping, persistence helpers)

MangaDirectorAgentSupport
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
