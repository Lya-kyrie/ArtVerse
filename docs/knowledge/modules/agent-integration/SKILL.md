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
| `AgentToolConfigurationRegistry` | Indexes tool configuration strategies by `AgentTaskType`; fails startup on missing or duplicate coverage. |
| `AgentToolGroupSupport` | Encapsulates AgentScope `Toolkit` group creation and tool registration. |
| `AgentScopeRuntimeContextFactory` | Creates AgentScope v2 `RuntimeContext` with `MangaAgentRuntimeContext` |

| `AgentModelSpecFactory` | Creates `AgentModelSpec` from config + user API key |

## Code Map — Workspace

| Class | Role |
|-------|------|
| `AgentWorkspaceService` | Creates per-user/story/conversation workspace directories, writes `KNOWLEDGE.md`/`AGENTS.md`/`MEMORY.md` |
| `AgentWorkspaceSyncService` | Builds chapter knowledge (source, storyboard, images, characters) → writes to workspace |

## Code Map — Models & Events

| Class | Role |
|-------|------|
| `AgentRunRequest` | Immutable request record passed to gateway |
| `AgentRunEvent` | Mapped agent event for SSE streaming; mapping is inlined in `MangaDirectorAgentNode` |
| `AgentModelSpec` | Model configuration: provider, baseUrl, model, apiKeyHash |
| `AgentTaskType` | Enum covering chat, novel, router, conversation, creative, storyboard, review, and director agents |
| `AgentMessage` | Simple role + content record |
| `MangaAgentPromptProvider` | System prompt for Manga Director agent |
| `MangaAgentRuntimeContext` | Per-call business context carried in `RuntimeContext` |

## Architecture Flow

```
MangaDirectorAgentNode
  → AgentScopeHarnessAgentGateway.streamEvents(request)
    → AgentScopeAgentFactory.getOrCreate(request)
      → buildAgent: HarnessAgent with sysPrompt, model, compaction, middleware, tool groups
    → AgentScopeRuntimeContextFactory.create(request)
      → RuntimeContext with sessionId, userId, MangaAgentRuntimeContext
    → agent.streamEvents(messages, ctx)
      → AgentScope v2 SDK
    → map events → AgentRunEvent → SSE
```

## Tool Groups

AgentScope `Toolkit` can register three tool groups:
- `context-tools`: `get_chapter_context` (read-only)
- `storyboard-tools`: `generate_storyboard`, `save_storyboard`, `save_structured_storyboard` (mutating)
- `hitl-tools`: `ask_user` (suspends agent)

Tool access is task-specific and is selected through `AgentToolConfigurationStrategy`:

- `CHAT`, `NOVEL`, `MANGA_ROUTER`: no ArtVerse business tools.
- `MANGA_CONVERSATION`, `MANGA_CREATIVE`, `MANGA_REVIEW`: context tools only.
- `MANGA_STORYBOARD`: context, storyboard, and HITL tools.
- `MANGA_DIRECTOR`: context and HITL tools.

## Key Decisions

- **Agent caching**: bounded Caffeine cache with configurable maximum size and expiry-after-access; evicted `HarnessAgent` instances are closed.
- **Model resolution**: User API key → dedicated non-streaming model; no key → system default model bean.
- **Workspace files**: `KNOWLEDGE.md` rewritten before each run. `AGENTS.md` and `MEMORY.md` written once.
- **HarnessAgent disables** shell and filesystem tools for business agents.
- **Retry boundary**: only the tool-free structured Router call is retried. Executor calls are never resubscribed after tools may have run.
- **Resilience isolation**: circuit breakers are partitioned by router/executor role, provider, and Base URL hash.
- **Exhaustive tool policy**: Every `AgentTaskType` must be owned by exactly one tool strategy. The registry rejects missing and duplicate mappings during Spring startup.

## Invariants

- `MangaAgentRuntimeContext` must carry userId, chapterId, conversationId, requestId, cozeApiKey for tool use.
- Workspace must be initialized before agent execution — `AgentWorkspaceService.initialize()` creates directories and default files.
- Agent cache key includes user, story, chapter, conversation, task type, provider, model, baseUrl hash, apiKey hash, prompt version, workspace path hash. Any config change → cache miss → new agent.
- HITL suspend uses AgentScope v2 `MiddlewareBase` (not deprecated `Hook`).
