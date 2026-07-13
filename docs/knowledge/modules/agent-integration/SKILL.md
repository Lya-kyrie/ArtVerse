---
name: agent-integration
description: AgentScope SDK integration — harness agent factory, gateway, runtime context, workspace sync, tool registration
---

# Agent Integration

Bridges ArtVerse business logic to the AgentScope Java v2 SDK (`agentscope-core` + `agentscope-harness`).

## Code Map — Gateway

| Class | Role |
|-------|------|
| `AgentScopeHarnessAgentGateway` | Message prep, harness invocation, event mapping, resilience (circuit breaker + retry) |

## Code Map — Factory & Context

| Class | Role |
|-------|------|
| `AgentScopeAgentFactory` | Creates/caches `HarnessAgent` instances per user/story/chapter/conversation/model/prompt. Registers tool groups. |
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
| `AgentTaskType` | Enum: `CHAT`, `NOVEL`, `MANGA_DIRECTOR` |
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

AgentScope `Toolkit` registers three tool groups:
- `context-tools`: `get_chapter_context` (read-only)
- `storyboard-tools`: `generate_storyboard`, `save_storyboard`, `save_structured_storyboard` (mutating)
- `hitl-tools`: `ask_user` (suspends agent)

## Key Decisions

- **Agent caching**: `ConcurrentHashMap<String, HarnessAgent>` — no LRU, no TTL. Monitor in production for memory growth.
- **Model resolution**: User API key → dedicated non-streaming model; no key → system default model bean.
- **Workspace files**: `KNOWLEDGE.md` rewritten before each run. `AGENTS.md` and `MEMORY.md` written once.
- **HarnessAgent disables** shell and filesystem tools for business agents.

## Invariants

- `MangaAgentRuntimeContext` must carry userId, chapterId, conversationId, requestId, cozeApiKey for tool use.
- Workspace must be initialized before agent execution — `AgentWorkspaceService.initialize()` creates directories and default files.
- Agent cache key includes user, story, chapter, conversation, task type, provider, model, baseUrl hash, apiKey hash, prompt version, workspace path hash. Any config change → cache miss → new agent.
- HITL suspend uses AgentScope v2 `MiddlewareBase` (not deprecated `Hook`).
