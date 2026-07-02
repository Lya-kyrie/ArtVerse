# Agent Integration — Call Graph

## Called By (inbound)

```
application/workflow/nodes/MangaDirectorAgentNode
  → AgentScopeHarnessAgentGateway.streamEvents(request)
    → AgentScopeAgentFactory.getOrCreate()
    → AgentScopeRuntimeContextFactory.create()

agent/AgentWorkspaceSyncService
  → AgentWorkspaceService.writeKnowledge()

agent/AgentModelSpecFactory
  → (called by MangaWorkflowOrchestrator for model spec creation)
```

## Calls Into (outbound)

```
AgentScopeAgentFactory
  → AgentWorkspaceService           (workspace path)
  → MangaAgentPromptProvider        (system prompt)
  → AgentModelSpecFactory           (model spec)

  → AgentScope v2 SDK:
      HarnessAgent, RuntimeContext, Toolkit, OpenAIChatModel, CompactionConfig
  → application/tools:
      MangaContextTools, MangaStoryboardTools, MangaHitlTools, MangaToolSupport
  → application:
      MangaImageRepository, SceneService, StructuredStoryboardService,
      ChapterAccessService, GenerationGuardService, AgentToolAuditService, AgentRunToolStatus

AgentScopeHarnessAgentGateway
  → AgentScope v2 SDK: HarnessAgent, Msg, AgentEvent, TextBlockDeltaEvent

AgentScopeRuntimeContextFactory
  → AgentScope v2 SDK: RuntimeContext
  → agent/MangaAgentRuntimeContext

AgentWorkspaceSyncService
  → ChapterRepository, MangaImageRepository  (persistence)
  → CharacterProfileService                 (application)
  → AgentWorkspaceService                   (agent)

AgentWorkspaceService
  → java.nio.file (Files, Path)

AgentModelSpecFactory
  → ArtVerseProperties   (config)
  → java.security.MessageDigest (SHA-256 for key hash)
```

## SDK Dependency Surface

All classes depend on `io.agentscope.*` (v2.0.0-RC3):

| SDK Module | Used By |
|------------|---------|
| `core.agent.RuntimeContext` | `AgentScopeRuntimeContextFactory` |
| `core.model.Model`, `OpenAIChatModel` | `AgentScopeAgentFactory`, `IntentClassificationModelProvider` |
| `core.message.Msg`, `MsgRole` | `AgentScopeHarnessAgentGateway` |
| `core.event.*` | `AgentScopeEventMapper`, `MangaDirectorAgentNode` |
| `core.tool.Toolkit`, `Tool` | `AgentScopeAgentFactory`, `MangaContextTools`, etc. |

| `harness.agent.HarnessAgent` | `AgentScopeAgentFactory`, `AgentScopeHarnessAgentGateway` |
| `harness.agent.memory.compaction.CompactionConfig` | `AgentScopeConfig` |

## Key Dependencies

| Downstream | Purpose |
|-----------|---------|
| `domain-model` | MangaAgentRuntimeContext carries entity IDs |
| `data-access` | ChapterRepository, MangaImageRepository |
| `application-services` | SceneService, ChapterAccessService, CharacterProfileService |
| `config` | ArtVerseProperties, AgentScopeConfig |
| `guard` | GenerationGuardService |
| **AgentScope SDK** | Core + Harness (external library) |
