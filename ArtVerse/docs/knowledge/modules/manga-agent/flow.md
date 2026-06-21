# Manga Agent Flow Reference

Read this file when the task depends on exact run lifecycle, event ordering, or API/frontend contracts.

## HTTP Contract

All endpoints are scoped to `/api/chapters/{chapterId}/manga-agent`.

- `GET /messages`: returns persisted user, assistant, and system messages for the current user and chapter.
- `POST /run`: synchronous run. Body: `{ message, requestId? }`. Response: `{ reply, requestId }`.
- `POST /run-stream`: streaming run. Body: `{ message, requestId? }`. Emits legacy business events (`status`, `run_event`, `tool`, `user_input_requested`, `done`, and `error`) plus AG-UI protocol events as default SSE `message` frames.
- `POST /ag-ui/run`: streaming run for AG-UI clients. Body: `{ message, requestId? }`. Emits only AG-UI protocol events as default SSE `message` frames, so the frontend can consume it through the official `HttpAgent` event pipeline without legacy event noise.
- `GET /runs/open`: returns the latest `RUNNING` or `WAITING_USER` run snapshot, if any.
- `GET /runs/{requestId}`: returns a persisted run snapshot with events.
- `POST /runs/{requestId}/resume`: synchronous resume. Body: `{ answer }`.
- `POST /runs/{requestId}/resume-stream`: streaming resume. Body: `{ answer }`.

Frontend types and stream parsing live in `frontend/src/api.ts`. The frontend depends on `@ag-ui/core` and `@ag-ui/client` for formal AG-UI event types. `ArtVerseMangaAgentHttpAgent` extends the official `HttpAgent` and adapts AG-UI `RunAgentInput` to the current ArtVerse `{ message, requestId }` body. The Manga Agent page restores open runs from persisted business events and consumes live AG-UI events in `frontend/src/components/MangaAgentPage.tsx`.

`MangaAgentPage.tsx` renders the execution panel from the same stream. Live progress should prefer AG-UI events: `RUN_STARTED`, `STATE_SNAPSHOT`, `STEP_STARTED`, `STEP_FINISHED`, `TOOL_CALL_START`, `TOOL_CALL_END`, `TOOL_CALL_RESULT`, `TEXT_MESSAGE_CHUNK`, `RUN_FINISHED`, and `RUN_ERROR`. The panel shows the active request id, latest run status, recent event timeline, tool activity, and human-in-the-loop waiting state. The panel is restored from persisted run events after refresh or reconnect.

## New Stream Run

1. `MangaAgentController.runStream` resolves the current user.
2. `MangaAgentService.runStream` creates an effective `requestId`, creates an `SseEmitter`, and submits work to `mangaGenerationExecutor`.
3. `AgentRunToolStatus.start` opens per-run tool tracking and forwards tool events to `MangaAgentRunEventPublisher`.
4. `runStreamLeader` validates message text and loads the visible chapter through `ChapterAccessService`.
5. `MangaAgentRunService.startOrReuse` creates or resumes a `MangaAgentRun` with status `RUNNING`.
6. A `status` SSE event announces context loading.
7. `GenerationGuardService.executeMangaAgentRun` protects the run with idempotency/rate-limit logic.
8. `prepareAgentMessages` saves the user message and builds history-limited agent messages.
9. `AgentWorkspaceSyncService.syncMangaDirectorKnowledge` writes `KNOWLEDGE.md` for the user/story workspace.
10. `buildRunRequest` creates an `AgentRunRequest` with user, story, chapter, task type, model, user API key, and request id.
11. `AgentScopeHarnessAgentGateway.streamEvents` sends messages to AgentScope.
12. `AgentScopeEventMapper` maps AgentScope events into `AgentRunEvent`; text deltas append to the final reply.
13. `MangaAgentRunEventPublisher` sends SSE events and persists non-text run events. It also maps the run lifecycle into formal AG-UI events through `AgUiEventFactory` and emits them as default SSE `message` frames.
14. On success, `MangaAgentRunService.markSucceeded` stores the final reply and `done` is emitted.
15. The frontend execution panel summarizes the latest events and keeps the user informed while the assistant response is still streaming.

## Human-In-The-Loop Resume

`MangaAgentToolFactory.ask_user` stores the current `AgentUserInputRequest` in `AgentRunToolStatus` using `AgentRunContext.requestId`, then throws `ToolSuspendException`.

`MangaAgentService` catches `AgentUserInputRequiredException`, marks the run `WAITING_USER`, emits `user_input_requested`, and completes the stream. The frontend displays the options and can call resume.

Resume requires the same `requestId`. `MangaAgentRunService.requireWaitingRun` verifies status. `MangaAgentConversationService.resumeMessage` combines original input, the stored question, and the user's answer into a continuation prompt. The run is marked `RUNNING` and the normal stream flow continues.

When `user_input_requested` is received or restored, the frontend execution panel switches to a waiting state and shows the selectable options. Free-text answers are allowed only when the backend request has `allowFreeText=true`.

## Persistence Rules

`manga_agent_runs` stores the current run status, input message, final reply, error, user input request JSON, and timestamps. The unique constraint is user plus request id.

`manga_agent_run_events` stores event name, type, phase, label, status, full JSON payload, and creation time. Persisted events allow the frontend to restore progress after refresh or reconnect.

Do not persist `text_delta` events by default; they can be numerous and are only needed while the stream is active.

## Recovery Behavior

If the agent returns an empty final response or fails after a successful mutating tool call, `MangaAgentConversationService.fallbackAfterToolSuccess` creates a degraded assistant reply and records a system failure message. The run becomes `DEGRADED` instead of losing the completed write.

If no mutating tool succeeded, failures are saved as system failure messages and surfaced as `FAILED`.

## Token Context Surfaces

There are three separate context channels:

- Conversation prompt from `MangaAgentConversationService.buildSystemPrompt` plus visible history.
- Story workspace knowledge from `AgentWorkspaceSyncService.buildKnowledge`, written to AgentScope `KNOWLEDGE.md`.
- AgentScope agent system prompt from `AgentScopeHarnessAgentGateway.systemPromptFor`.

When reducing token usage, prefer shrinking history, story knowledge excerpts, or duplicated prompt instructions before changing business behavior.
