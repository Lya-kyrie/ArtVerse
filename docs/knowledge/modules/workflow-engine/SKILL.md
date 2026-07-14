---
name: workflow-engine
description: Manga agent workflow orchestration - context assembly, node dispatch, streaming execution, HITL resume
---

# Workflow Engine

The manga workflow uses capability-validated automatic routing across 5 routes: `CONVERSATION`, `CREATIVE`, `STORYBOARD`, `REVIEW`, and `DIRECTOR`. `DIRECTOR` is reserved for validated multi-step plans (2-3 steps); single-intent requests dispatch directly to a specialist (Conversation, Creative, Storyboard, or Review).

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
| `MangaWorkflowRoute` | Specialist routes plus `DIRECTOR`; each route declares its business capabilities |
| `MangaWorkflowNode` | Pipeline stages: `COLLECTING_CONTEXT`, `GENERATING`, `EVALUATING`, `WAITING_USER`, `COMPLETED` |
| `MangaWorkflowResult` | Node execution result with degraded flag |

## Code Map - Nodes

| Class | Route | Behavior |
|-------|-------|----------|
| `MangaConversationAgentNode` | `CONVERSATION` | Read-only Q&A agent for status queries, help, and general discussion. Uses context tools only. No write tools, no HITL, no DEGRADED fallback. |
| `MangaCreativeAgentNode` | `CREATIVE` | Read-only creative discussion agent for plot, character, world-building, and storyboard ideas. Uses context tools only. No write tools, no HITL, no DEGRADED fallback. |
| `MangaStoryboardAgentNode` | `STORYBOARD` | Sole mutating agent. Generates, edits, and persists storyboard scenes. Includes write-guard validation, DEGRADED fallback when tools succeed but the final response fails, and HITL confirmation via `ask_user`. |
| `MangaReviewAgentNode` | `REVIEW` | Read-only review agent for quality and consistency analysis. Uses parallel multi-dimensional review (camera language, character consistency, pacing, continuity). No write tools, no DEGRADED fallback. |
| `MangaDirectorAgentNode` | `DIRECTOR` | Restores and executes a persisted, validated multi-step plan and owns the single final conversation reply. Only invoked when Router detects more than one suggested step. |
| `MangaAgentExecutionSupport` | helper | Shared request construction, workspace sync, AgentScope event mapping, and specialist execution support used by all five specialist nodes. |

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

- Every workflow route must have exactly one registered handler; duplicate or missing handlers fail startup.
- Child steps in a Director plan must not persist user/assistant conversation messages. Director owns the final reply.
- A mutating step failure is `FAILED`; `DEGRADED` is reserved for a successful mutation followed by a response failure.
- Missing route handlers must fail fast; never silently fall back to `DIRECTOR`.
- `requestId` is the idempotency/resume key. Preserve it across retries and resumes.
- Mutating tools that succeed but fail the final reply become `DEGRADED`, not `FAILED`.
- `MangaWorkflowOrchestrator` should not build AgentScope requests directly; keep AgentScope request/event details inside `MangaDirectorAgentNode` and `MangaDirectorAgentSupport`.
- See `docs/knowledge/modules/manga-agent/flow.md` for detailed run lifecycle and event ordering.
