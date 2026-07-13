# Manga Agent Flow Reference

Read this file when the task depends on exact run lifecycle, event ordering, or API/frontend contracts.

## HTTP Contract

All endpoints are scoped to `/api/chapters/{chapterId}/manga-agent`.

- `GET /messages`: returns persisted user, assistant, and system messages for the current user and chapter.
- `GET /conversations`: returns Manga Agent conversations for the current user and chapter.
- `POST /conversations`: archives the active conversation, creates a fresh active conversation, and returns it.
- `POST /conversations/{conversationId}/archive`: archives a specific conversation.
- `DELETE /conversations/{conversationId}`: deletes a specific conversation.
- `GET /conversations/{conversationId}/messages`: returns messages for one conversation.
- `POST /conversations/{conversationId}/ag-ui/run`: preferred AG-UI streaming run for a specific conversation. Body includes `message`, optional `requestId`, and provider/model fields.
- `GET /conversations/{conversationId}/runs/open`: returns the latest open run for one conversation.
- `GET /conversations/{conversationId}/runs/{requestId}`: returns a run snapshot scoped to one conversation.
- `POST /conversations/{conversationId}/runs/{requestId}/cancel`: cancels a run scoped to one conversation.
- `POST /conversations/{conversationId}/ag-ui/runs/{requestId}/resume`: preferred AG-UI streaming resume for one conversation.
- `POST /run`: synchronous run. Body includes `message`, optional `requestId`, and provider/model fields. Response: `{ reply, requestId }`.
- `POST /ag-ui/run`: default streaming run for AG-UI clients. Body includes `message`, optional `requestId`, and provider/model fields. Emits AG-UI protocol events as default SSE `message` frames.
- `GET /runs/open`: returns the latest `RUNNING` or `WAITING_USER` run snapshot, if any.
- `GET /runs/{requestId}`: returns a persisted run snapshot with events.
- `POST /runs/{requestId}/cancel`: marks an open run as `CANCELLED`. The frontend should also abort its active AG-UI subscription, but persisted run state is the source of truth.
- `POST /runs/{requestId}/resume`: synchronous resume. Body: `{ answer }` plus provider/model fields.
- `POST /ag-ui/runs/{requestId}/resume`: default AG-UI streaming resume. Body: `{ answer }` plus provider/model fields.

Frontend types and stream parsing live in `frontend/src/api.ts`. The frontend depends on `@ag-ui/core` and `@ag-ui/client` for formal AG-UI event types. `ArtVerseMangaAgentHttpAgent` extends the official `HttpAgent` and adapts AG-UI `RunAgentInput` to the current ArtVerse body. The Manga Agent page no longer exposes a route selector; current execution always uses the `DIRECTOR` workflow. The left sidebar lists chapter conversations explicitly, and switching a conversation reloads messages plus open-run state.

`MangaAgentPage.tsx` renders the execution panel from AG-UI and persisted run events. Live progress should prefer `RUN_STARTED`, `STATE_SNAPSHOT`, `CUSTOM` run/tool audit events, `TEXT_MESSAGE_START`, `TEXT_MESSAGE_CONTENT`, `TEXT_MESSAGE_END`, `RUN_FINISHED`, and `RUN_ERROR`. The panel shows the active request id, latest run status, recent event timeline, tool activity, cancel action, and human-in-the-loop waiting state.

## New Stream Run

1. `MangaAgentController` resolves the current user.
2. `MangaAgentService` resolves the active conversation or supplied conversation id, creates an effective `requestId`, creates an `SseEmitter`, and submits work to `mangaGenerationExecutor` through `AgentConcurrencyGate`.
3. `AgentRunToolStatus.start` opens per-run tool tracking and forwards tool events to `MangaAgentRunEventPublisher`.
4. `MangaWorkflowOrchestrator.runStreamLeader` validates message text and starts or reuses a conversation-scoped `MangaAgentRun`.
5. A `status` SSE event announces that the agent started processing the chapter.
6. `GenerationGuardService.executeMangaAgentRun` protects the run with idempotency/rate-limit logic.
7. `MangaWorkflowContextAssembler` builds story, chapter, image, character, and conversation metadata.
8. `MangaWorkflowNodeRegistry.handlerFor(DIRECTOR)` selects `MangaDirectorAgentNode`; missing handlers fail fast instead of falling back.
9. `MangaDirectorAgentSupport.prepareAgentMessages` saves the user message and builds history-limited agent messages from the selected conversation only.
10. `AgentWorkspaceSyncService.syncMangaDirectorKnowledge` writes `KNOWLEDGE.md` for the user/story workspace.
11. `MangaDirectorAgentSupport.buildRunRequest` creates an `AgentRunRequest` with user, story, chapter, conversation id, task type, model, user API key, and request id.
12. `AgentScopeHarnessAgentGateway.streamEvents` obtains the cached `HarnessAgent`, builds per-call runtime context, and sends messages to AgentScope.
13. `MangaDirectorAgentSupport.mapAgentScopeEvent` maps AgentScope events into `AgentRunEvent`; `MangaDirectorAgentNode` accumulates text deltas into the final reply.
14. `MangaAgentRunEventPublisher` sends SSE events and persists non-text run events. It maps run lifecycle into formal AG-UI events.
15. On success, `MangaWorkflowOrchestrator.completeRun` marks the run `SUCCEEDED` or `DEGRADED` and emits `done`.
16. The frontend synchronizes final persisted messages after `RUN_FINISHED`.

## Human-In-The-Loop Resume

`AgentScopeRuntimeContextFactory` adds `MangaAgentRuntimeContext` to AgentScope v2 `RuntimeContext` for Manga Director runs. Tools read user id, chapter id, conversation id, request id, and Coze API key from that typed context instead of relying on factory-captured fields.

`MangaHitlTools.ask_user` stores the current `AgentUserInputRequest` in `AgentRunToolStatus`, then throws `ToolSuspendException`. `MangaDirectorAgentNode` detects the tool-suspended waiting state and raises `AgentUserInputRequiredException`. `MangaAgentService` catches it, marks the run `WAITING_USER`, emits `user_input_requested`, and completes the stream.

Resume requires the same `conversationId` and `requestId`. `MangaAgentService` verifies a `WAITING_USER` run, reconstructs a continuation message through `MangaAgentConversationService.resumeMessage`, and continues the same `DIRECTOR` route.

## Cancellation And Interruption

`CANCELLED` means the user explicitly stopped the run through `POST /runs/{requestId}/cancel`. The frontend aborts the active AG-UI subscription after the backend confirms cancellation. Terminal writes from the background worker must not overwrite `CANCELLED`.

`INTERRUPTED` means the system repaired a stalled `RUNNING` run. The runtime has no total-duration timeout: a long task may continue while it produces real AgentScope or tool progress. The stream allows 90 seconds for the first event, then applies an idle timeout of 180 seconds for model work and 600 seconds for tool work. `last_progress_at` and `current_phase` are updated only by real model/tool events, not SSE keepalives. A scheduled watchdog and state reads interrupt runs whose last real progress exceeds the applicable idle budget.

## Persistence Rules

`manga_agent_conversations` stores chapter-level conversation sessions. Creating a new conversation archives the previous active conversation and gives the user a clean message/run/session scope without deleting old records.

`manga_agent_messages` belongs to one conversation. Legacy chapter-level message endpoints resolve the current active conversation for compatibility.

`manga_agent_runs` stores status, route, input message, final reply, error, user input request JSON, and timestamps. Runs belong to one conversation. New executions use `DIRECTOR`; older migrations may mention planned routes, but no non-director handler is registered in current code. Valid statuses are `RUNNING`, `WAITING_USER`, `SUCCEEDED`, `DEGRADED`, `FAILED`, `CANCELLED`, and `INTERRUPTED`.

`manga_agent_run_events` stores event name, type, phase, label, status, full JSON payload, and creation time. Persisted events allow the frontend to restore progress after refresh or reconnect. Do not persist `text_delta` events by default.

## Recovery Behavior

If the agent returns an empty final response or fails after a successful mutating tool call, `MangaAgentConversationService.fallbackAfterToolSuccess` creates a degraded assistant reply and records a system failure message. The run becomes `DEGRADED` instead of losing the completed write.

If no mutating tool succeeded, failures are saved as system failure messages and surfaced as `FAILED`.

## Token Context Surfaces

There are three separate context channels:

- Conversation prompt from `MangaAgentConversationService.buildSystemPrompt` plus visible history from the selected conversation.
- Story workspace knowledge from `AgentWorkspaceSyncService.buildKnowledge`, written to AgentScope `KNOWLEDGE.md`.
- AgentScope agent system prompt from `MangaAgentPromptProvider`.

AgentScope `RuntimeContext.sessionId` includes user, story, chapter, conversation id, and task suffix. A new conversation must produce a new session id. `RuntimeContext.userId` remains the ArtVerse user id for multi-tenant isolation. `MangaAgentRuntimeContext` carries the per-call business context that tools need.
