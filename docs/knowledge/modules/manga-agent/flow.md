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
- `POST /conversations/{conversationId}/ag-ui/run`: preferred AG-UI streaming run for a specific conversation. Body includes `message`, optional `requestId`, `configId`, and optional model override. Raw provider/baseUrl/apiKey fields are deprecated compatibility fields.
- `GET /conversations/{conversationId}/runs/open`: returns the latest open run for one conversation.
- `GET /conversations/{conversationId}/runs/{requestId}`: returns a run snapshot scoped to one conversation.
- `POST /conversations/{conversationId}/runs/{requestId}/cancel`: cancels a run scoped to one conversation.
- `POST /conversations/{conversationId}/ag-ui/runs/{requestId}/resume`: preferred AG-UI streaming resume for one conversation.
- `POST /run`: synchronous run. Body includes `message`, optional `requestId`, and provider/model fields. Response: `{ reply, requestId }`.
- `POST /ag-ui/run`: default streaming run for AG-UI clients. Body includes `message`, optional `requestId`, and provider/model fields. Emits AG-UI protocol events as default SSE `message` frames.
- `GET /runs/open`: returns the latest `RUNNING` or `WAITING_USER` run snapshot, if any.
- `GET /runs/{requestId}`: returns a persisted run snapshot with events.
- `GET /runs/{requestId}/events`: replays and tails durable events after optional `Last-Event-ID`; frames carry database event ids and can be served by any instance.
- `GET /runs/{requestId}/artifacts`: restores storyboard drafts and structured evaluation results.
- `POST /runs/{requestId}/cancel`: marks an open run as `CANCELLED`. The frontend should also abort its active AG-UI subscription, but persisted run state is the source of truth.
- `POST /runs/{requestId}/resume`: synchronous resume. Body: `{ answer }` plus provider/model fields.
- `POST /ag-ui/runs/{requestId}/resume`: default AG-UI streaming resume. Body: `{ answer }` plus provider/model fields.

Story chat reuses the same durable run/event/artifact substrate under `/api/chapters/{chapterId}/story-chat`:

- `POST /conversations/{conversationId}/ag-ui/run`: starts a story-chat AG-UI run. The service writes `run_type=STORY_CHAT` and a generic `route_key` such as `READ`, `DRAFT`, `POLISH`, `REVIEW`, or `WRITE`.
- `POST /conversations/{conversationId}/ag-ui/runs/{requestId}/resume`: resumes a waiting story-chat run with `{ decision: "confirm" | "discard", artifact_id }`.
- `GET /conversations/{conversationId}/runs/open`, `GET /runs/{requestId}`, `GET /runs/{requestId}/events`, `GET /runs/{requestId}/artifacts`, and `POST /runs/{requestId}/cancel` mirror the Manga Agent semantics.

Frontend types and stream parsing live in `frontend/src/api.ts`. The frontend depends on `@ag-ui/core` and `@ag-ui/client` for formal AG-UI event types. `ArtVerseMangaAgentHttpAgent` extends the official `HttpAgent` and adapts AG-UI `RunAgentInput` to the current ArtVerse body. The Manga Agent page no longer exposes a route selector; the semantic router selects a specialist directly and uses `DIRECTOR` only for validated compound requests. The left sidebar lists chapter conversations explicitly, and switching a conversation reloads messages plus open-run state.

`MangaAgentPage.tsx` renders the execution panel from AG-UI and persisted run events. Live progress should prefer `RUN_STARTED`, `STATE_SNAPSHOT`, `CUSTOM` run/tool audit events, `TEXT_MESSAGE_START`, `TEXT_MESSAGE_CONTENT`, `TEXT_MESSAGE_END`, `RUN_FINISHED`, and `RUN_ERROR`. The panel shows the active request id, latest run status, recent event timeline, tool activity, cancel action, and human-in-the-loop waiting state.

## New Stream Run

1. `MangaAgentController` resolves the current user.
2. `MangaAgentService` resolves the active conversation or supplied conversation id, creates an effective `requestId`, creates an `SseEmitter`, and submits work to `mangaGenerationExecutor` through `AgentConcurrencyGate`.
3. `AgentRunToolStatus.start` opens per-run tool tracking and forwards tool events to `MangaAgentRunEventPublisher`.
4. `MangaWorkflowOrchestrator.runStreamLeader` validates message text and checks for a persisted assistant reply before creating or reusing a run. A cache hit is replayed without creating a synthetic `RUNNING` record; an existing cancelled or interrupted run remains authoritative.
5. On a cache miss, the orchestrator starts or reuses a conversation-scoped `MangaAgentRun`, then a `status` SSE event announces that the agent started processing the chapter.
6. `GenerationGuardService.executeMangaAgentRun` protects the run with idempotency/rate-limit logic.
7. `MangaWorkflowContextAssembler` builds story, chapter, image, character, and conversation metadata.
8. `MangaWorkflowNodeRegistry.handlerFor(route)` selects the routed specialist or `MangaDirectorAgentNode` for a validated compound plan; missing handlers fail fast instead of falling back.
9. `MangaAgentExecutionSupport.prepareAgentMessages` saves the user message and builds history-limited agent messages from the selected conversation only.
10. `MangaAgentExecutionSupport` performs deterministic hybrid RAG, records the immutable knowledge snapshot/context hash, and injects approved facts as sourced untrusted data blocks.
11. `AgentWorkspaceSyncService.syncMangaDirectorKnowledge` writes `KNOWLEDGE.md` to the conversation-scoped workspace projection.
12. `MangaAgentExecutionSupport.buildRunRequest` creates an `AgentRunRequest` with server-owned identity, request, step, fencing, model-config, and Skill-version values.
13. `AgentScopeHarnessAgentGateway.streamEvents` consumes the route budget, obtains the Skill-versioned cached `HarnessAgent`, builds runtime context, and sends messages to AgentScope.
14. `MangaAgentExecutionSupport.mapAgentScopeEvent` maps AgentScope events into `AgentRunEvent`. Director child-step events carry `planId`, `step`, `route`, and `agentName`; child text is retained as handoff context but suppressed from the primary reply stream.
15. `MangaAgentRunEventPublisher` persists non-text events before publishing Redis Stream notifications and sending live AG-UI events.
16. On success, `MangaWorkflowOrchestrator.completeRun` marks the run `SUCCEEDED` or `DEGRADED` and emits `done`.
17. The frontend synchronizes final persisted messages after `RUN_FINISHED`; an open run is tailed from its largest persisted event id.

## Human-In-The-Loop Resume

`AgentScopeRuntimeContextFactory` adds `MangaAgentRuntimeContext` to AgentScope v2 `RuntimeContext` for Manga Director runs. Tools read user id, chapter id, conversation id, request id, and Coze API key from that typed context instead of relying on factory-captured fields.

`MangaHitlTools.ask_user` stores the current `AgentUserInputRequest` in `AgentRunToolStatus`, then throws `ToolSuspendException`. `MangaDirectorAgentNode` detects the tool-suspended waiting state and raises `AgentUserInputRequiredException`. `MangaAgentService` catches it, marks the run `WAITING_USER`, emits `user_input_requested`, and completes the stream.

Resume requires the same `conversationId` and `requestId`. `MangaAgentService` verifies a `WAITING_USER` run, reconstructs a continuation message through `MangaAgentConversationService.resumeMessage`, and continues the same `DIRECTOR` route.

## Cancellation And Interruption

`CANCELLED` means the user explicitly stopped the run through `POST /runs/{requestId}/cancel`. The frontend aborts the active AG-UI subscription after the backend confirms cancellation. Terminal writes from the background worker must not overwrite `CANCELLED`.

The cancel endpoint marks the active in-memory tool/run state before removing its lookup entry, so an already-running worker observes cancellation and stops consuming model events. Terminal transitions lock the run row; cancellation, success, failure, and cache reconciliation therefore use first-terminal-transition-wins semantics instead of racing on a stale `RUNNING` entity.

`INTERRUPTED` means the system repaired a stalled `RUNNING` run. The runtime has no total-duration timeout: a long task may continue while it produces real AgentScope or tool progress. The stream allows 90 seconds for the first event, then applies an idle timeout of 180 seconds for model work and 600 seconds for tool work. `last_progress_at` and `current_phase` are updated only by real model/tool events, not SSE keepalives. A scheduled watchdog and state reads interrupt runs whose last real progress exceeds the applicable idle budget.

## Persistence Rules

`manga_agent_conversations` stores chapter-level conversation sessions. Creating a new conversation archives the previous active conversation and gives the user a clean message/run/session scope without deleting old records.

`manga_agent_messages` belongs to one conversation. Legacy chapter-level message endpoints resolve the current active conversation for compatibility.

`manga_agent_runs` stores status, route, input/final/error data, waiting/routing/plan JSON, workflow/trace/model/Skill/knowledge/budget metadata, lease owner, fencing token, and timestamps. Runs belong to one conversation. New executions are routed by `MangaWorkflowRouter`; `ExecutionPlanCompiler` creates at most three validated Director steps. Valid statuses are `RUNNING`, `WAITING_USER`, `SUCCEEDED`, `DEGRADED`, `FAILED`, `CANCELLED`, and `INTERRUPTED`.

The physical table name remains `manga_agent_runs`, but rows also carry `run_type` and `route_key`. Manga rows use `run_type=MANGA_AGENT`; story-chat rows use `run_type=STORY_CHAT` and keep the legacy enum `route` compatible while `route_key` holds story-chat routes.

`manga_agent_run_steps` is the durable step journal used to resume from the first unfinished compiled step. `manga_agent_run_artifacts` stores storyboard drafts, validation/evaluation results, checksums, and commit status. A chapter changes only through `commit_storyboard`, which validates artifact state, chapter version, idempotency key, and fencing token.

For story chat, `manga_agent_run_artifacts` also stores `NOVEL_CONTENT_DRAFT` complete chapter snapshots. `draft_novel_content` records content hash, base chapter version, and word counts without changing the chapter. `commit_novel_content` requires the current run, current user, matching chapter, confirmed artifact id, current lease/fencing token, unchanged base version, and matching hash before it creates a `chapter_novel_revisions` row linked by `agent_run_artifact_id` and updates `chapters.novel_content`.

`manga_agent_run_events` stores event name, type, phase, label, status, full JSON payload, creation time, and its monotonic database id. Persisted events are the replay/audit source; Redis Stream is the cross-instance live notification channel. Do not persist `text_delta` events by default.

`agent_outbox_events` records chapter/storyboard changes and knowledge decisions. A worker claims rows with `SKIP LOCKED`, renews a lease, and completes/fails only with the current fencing token. Chapter events use the user's active saved LLM profile to create `PENDING` knowledge candidates; missing BYOK configuration is retried and never replaced with a platform key.

`agent_workspace_items` is the shared AgentScope `RemoteFilesystem` backing store. Workspace paths are logical; business projections must not depend on an application instance's local disk.

## Recovery Behavior

If the agent returns an empty final response or fails after a successful mutating tool call, `MangaAgentConversationService.fallbackAfterToolSuccess` creates a degraded assistant reply and records a system failure message. The run becomes `DEGRADED` instead of losing the completed write.

If no mutating tool succeeded, failures are saved as system failure messages and surfaced as `FAILED`.

## Token Context Surfaces

There are four separate context channels:

- Conversation prompt from `MangaAgentConversationService.buildSystemPrompt` plus visible history from the selected conversation.
- Approved RAG knowledge selected by deterministic hybrid retrieval and injected with source/version/score/context hash.
- Conversation workspace projection from `AgentWorkspaceSyncService.buildKnowledge`, written to AgentScope `KNOWLEDGE.md` but never treated as the business fact source.
- AgentScope agent system prompt from `MangaAgentPromptProvider`.

AgentScope `RuntimeContext.sessionId` includes user, story, chapter, conversation id, and task suffix. A new conversation must produce a new session id. `RuntimeContext.userId` remains the ArtVerse user id for multi-tenant isolation. `MangaAgentRuntimeContext` carries the per-call business context that tools need.
