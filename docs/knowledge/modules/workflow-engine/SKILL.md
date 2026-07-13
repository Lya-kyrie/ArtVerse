---
name: workflow-engine
description: Manga agent workflow orchestration - context assembly, node dispatch, streaming execution, HITL resume
---

# Workflow Engine

The current manga workflow has one execution route: `DIRECTOR`. Keep the route abstraction explicit, but do not document or add pseudo-routes until product behavior and node handlers actually exist.

## Code Map - Workflow Core

| Class | Role |
|-------|------|
| `MangaWorkflowOrchestrator` | Top-level coordinator: validates input, applies guard lifecycle, starts/reuses runs, emits workflow-level status events, dispatches to a node handler |
| `MangaWorkflowContextAssembler` | Builds chapter/story/image/character/conversation context snapshots and warning summaries |
| `MangaWorkflowNodeRegistry` | Spring DI-based node registry; missing handlers fail fast instead of falling back to `DIRECTOR` |
| `MangaWorkflowNodeHandler` | Interface: `route()`, `run()`, `stream()` |
| `MangaWorkflowExecutionContext` | Immutable context record for node execution |
| `MangaWorkflowContextSnapshot` | Chapter state snapshot passed to nodes |
| `MangaWorkflowStreamContext` | SSE sink plus run reference for streaming nodes |
| `MangaWorkflowRoute` | Enum: `DIRECTOR` only |
| `MangaWorkflowNode` | Pipeline stages: `COLLECTING_CONTEXT`, `GENERATING`, `EVALUATING`, `WAITING_USER`, `COMPLETED` |
| `MangaWorkflowResult` | Node execution result with degraded flag |

## Code Map - Nodes

| Class | Route | Behavior |
|-------|-------|----------|
| `MangaDirectorAgentNode` | `DIRECTOR` | Calls AgentScope through the harness gateway and persists final/degraded replies |
| `MangaDirectorAgentSupport` | helper | Builds `AgentRunRequest`, syncs workspace knowledge, maps AgentScope events, and saves reply/failure messages |
| `AbstractStaticReplyNode` | base | Dormant base for future static nodes; no concrete non-director nodes are registered |

## Key Flows

### New Stream Run
1. `MangaAgentService` resolves the conversation, opens the stream shell, and submits the worker through the concurrency gate.
2. `MangaWorkflowOrchestrator.runStreamLeader()` validates message text and starts or reuses a `MangaAgentRun`.
3. `GenerationGuardService.executeMangaAgentRun()` applies idempotency/rate-limit protection.
4. `MangaWorkflowContextAssembler` assembles context and emits the context summary event.
5. `MangaWorkflowNodeRegistry.handlerFor(DIRECTOR).stream()` dispatches to `MangaDirectorAgentNode`.
6. `MangaDirectorAgentNode` delegates request construction and event mapping to `MangaDirectorAgentSupport`, calls AgentScope, and returns the reply/degraded payload.
7. `MangaWorkflowOrchestrator.completeRun()` marks the run terminal and emits the done event unless the run was already cancelled.

### HITL Resume
1. `ask_user` stores `AgentUserInputRequest` in `AgentRunToolStatus` and suspends the agent.
2. `MangaAgentService` catches `AgentUserInputRequiredException`, marks the run `WAITING_USER`, and emits `user_input_requested`.
3. Resume requires the same `requestId` and conversation. The continuation message is reconstructed from the stored request and the user's answer.
4. The resumed stream continues through the same `DIRECTOR` route and request id.

## Invariants

- `DIRECTOR` is the only registered workflow route today.
- Missing route handlers must fail fast; never silently fall back to `DIRECTOR`.
- `requestId` is the idempotency/resume key. Preserve it across retries and resumes.
- Mutating tools that succeed but fail the final reply become `DEGRADED`, not `FAILED`.
- `MangaWorkflowOrchestrator` should not build AgentScope requests directly; keep AgentScope request/event details inside `MangaDirectorAgentNode` and `MangaDirectorAgentSupport`.
- See `docs/knowledge/modules/manga-agent/flow.md` for detailed run lifecycle and event ordering.
