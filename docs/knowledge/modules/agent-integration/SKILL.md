---
name: agent-integration
description: AgentScope SDK integration — harness agent factory, gateway, runtime context, workspace sync, tool registration
---

# Agent Integration

Bridges ArtVerse business logic to the AgentScope Java v2 SDK (`agentscope-core` + `agentscope-harness`).

## Code Map — Gateway

| Class | Role |
|-------|------|
| `AgentScopeHarnessAgentGateway` | Harness invocation, event mapping, resilience (circuit breaker + retry) |
| `AgentScopeMessageMapper` | Normalizes system messages and maps ArtVerse messages to AgentScope `Msg` inputs |

## Code Map — Factory & Context

| Class | Role |
|-------|------|
| `AgentScopeAgentFactory` | Creates/caches `HarnessAgent` instances per user/story/chapter/conversation/model/prompt. Delegates tool setup by task type. |
| `ArtVerseSkillRepository` | Read-only AgentScope Skill repository backed only by published platform manifests. |
| `ArtVerseSkillRegistry` | Resolves capabilities to fixed published Skill versions and applies user settings only to configurable Skills. |
| `AgentToolConfigurationRegistry` | Indexes tool configuration strategies by `AgentTaskType`; fails startup on missing or duplicate coverage. |
| `AgentToolGroupSupport` | Encapsulates AgentScope `Toolkit` group creation and tool registration. |
| `AgentScopeRuntimeContextFactory` | Creates AgentScope v2 `RuntimeContext` with `MangaAgentRuntimeContext` |

| `AgentModelSpecFactory` | Creates `AgentModelSpec` from config + user API key after endpoint/header policy validation. |

## Code Map — Workspace

| Class | Role |
|-------|------|
| `AgentWorkspaceService` | Creates tenant/user/story/conversation read-only workspace projections and writes `KNOWLEDGE.md`/`AGENTS.md`/`MEMORY.md`. |
| `AgentWorkspaceSyncService` | Builds a chapter projection from PostgreSQL facts and writes it only to the matching conversation workspace. |
| `PostgresAgentWorkspaceStore` | AgentScope `BaseStore` implementation with optimistic versions; used by Harness `RemoteFilesystem` instead of local files. |

## Code Map — Models & Events

| Class | Role |
|-------|------|
| `AgentRunRequest` | Immutable request record passed to gateway |
| `AgentRunEvent` | Mapped agent event for SSE streaming; mapping is centralized in `MangaAgentExecutionSupport`. |
| `AgentModelSpec` | Model configuration: provider, baseUrl, model, apiKeyHash |
| `AgentTaskType` | Enum covering chat, novel, router, conversation, creative, storyboard, review, director, and knowledge-extraction agents |
| `AgentMessage` | Simple role + content record |
| `MangaAgentPromptProvider` | System prompt for Manga Director agent |
| `MangaAgentRuntimeContext` | Per-call business context carried in `RuntimeContext` |

## Architecture Flow

```
MangaWorkflowNode
  → AgentScopeHarnessAgentGateway.streamEvents(request)
    → AgentScopeAgentFactory.getOrCreate(request)
      → buildAgent: HarnessAgent with sysPrompt, model, controlled Skill, compaction, middleware, tool groups
    → AgentScopeRuntimeContextFactory.create(request)
      → RuntimeContext with sessionId, userId, MangaAgentRuntimeContext
    → agent.streamEvents(messages, ctx)
      → AgentScope v2 SDK
    → map events → AgentRunEvent → SSE
```

## Tool Groups

AgentScope `Toolkit` can register three tool groups:
- `context-tools`: `get_chapter_context` (read-only)
- `storyboard-tools`: `draft_structured_storyboard` (draft/evaluate) and `commit_storyboard` (sole write); legacy storyboard tools are adapters
- `hitl-tools`: `ask_user` (suspends agent)

Tool access is task-specific and is selected through `AgentToolConfigurationStrategy`:

- `CHAT`, `NOVEL`, `MANGA_ROUTER`, `KNOWLEDGE_EXTRACTION`: no ArtVerse business tools.
- `MANGA_CONVERSATION`, `MANGA_CREATIVE`, `MANGA_REVIEW`: context tools only.
- `MANGA_STORYBOARD`: context, storyboard, and HITL tools.
- `MANGA_DIRECTOR`: context and HITL tools.

## Key Decisions

- **Agent caching**: bounded Caffeine cache with configurable maximum size and expiry-after-access; evicted `HarnessAgent` instances are closed.
- **Skill resolution**: the Router emits capabilities only. Application code selects a published checksum/version and disables AgentScope dynamic/default workspace Skills.
- **Model resolution**: BYOK config id and model resolve to a dedicated model after HTTPS, DNS/private-network, and header validation. Raw provider fields remain compatibility-only; no agent path falls back to an operator API key.
- **Workspace files**: `KNOWLEDGE.md` is a conversation-scoped projection rewritten before each run through AgentScope `RemoteFilesystem`. PostgreSQL messages and approved knowledge remain sources of truth.
- **Session hydration**: `AgentSessionHydrator` avoids duplicating PostgreSQL history when Redis AgentState exists and supplies recent persisted history only for recovery.
- **Secret storage**: provider keys and embedding headers use randomized, version-prefixed AES-GCM encryption; legacy AES values remain readable for migration.
- **HarnessAgent disables** shell and filesystem tools for business agents.
- **Budgets**: model and Tool calls are consumed atomically in Redis and persisted to `agent_usage_ledger`; exhaustion stops further implicit calls.
- **Run ownership**: workers claim a PostgreSQL lease with a fencing token and renew it every 30 seconds. Mutating commit verifies that token.
- **Retry boundary**: only the tool-free structured Router call is retried. Executor calls are never resubscribed after tools may have run.
- **Resilience isolation**: circuit breakers are partitioned by router/executor role, provider, and Base URL hash.
- **Exhaustive tool policy**: Every `AgentTaskType` must be owned by exactly one tool strategy. The registry rejects missing and duplicate mappings during Spring startup.

## Invariants

- `MangaAgentRuntimeContext` must carry server-owned userId, optional tenantId, storyId, chapterId, conversationId, requestId, stepId, fencing token, and required provider secrets for Tool use. Model-provided identity fields are ignored.
- Workspace must be initialized before agent execution; `AgentWorkspaceService` creates versioned default objects in the shared store, not local directories.
- Agent cache key includes user, story, chapter, conversation, task type, provider, model, baseUrl hash, apiKey hash, prompt version, Skill version, and workspace path hash. Any config change causes a cache miss and a new agent.
- HITL suspend uses AgentScope v2 `MiddlewareBase` (not deprecated `Hook`).
