# AgentScope v2 Refactor Plan

## Background

The Manga Agent already depends on `io.agentscope:agentscope-harness:2.0.0-RC3`, but part of the application code still follows an older orchestration style. The current implementation wraps AgentScope with a business-heavy gateway and passes per-run tool data through factory-captured fields instead of the v2 `RuntimeContext` model.

The target architecture should align with AgentScope Java v2:

- `HarnessAgent` is the long-running agent entrypoint.
- `RuntimeContext` carries per-call metadata such as `userId`, `sessionId`, request id, and business context.
- Tool instances are close to stateless and receive business data from injected context.
- Agent state, workspace, and run persistence remain separate responsibilities.
- AG-UI/SSE delivery stays in the application layer, not inside the AgentScope adapter.

## Current Pain Points

- `AgentScopeHarnessAgentGateway` mixes model resolution, cache-key construction, workspace selection, prompt selection, tool registration, message conversion, and `RuntimeContext` construction.
- `MangaAgentService` owns conversation state, idempotency, run persistence, SSE publishing, AgentScope execution, HITL resume, cancellation checks, and degraded fallback handling.
- `MangaAgentToolFactory` captures `cozeApiKey`, `chapterId`, and `userId` at tool creation time. That makes the tool object session-specific and prevents clean reuse of a single `HarnessAgent` instance per task/model/workspace.
- HITL currently uses `ask_user` plus `ToolSuspendException` and business-side state lookup. This is compatible with the existing frontend, but it is not yet modeled around v2 permission/external-execution events.
- Several visible Chinese strings in the Manga Agent path are mojibake. Treat these as correctness defects.

## Target Boundaries

### AgentScope Adapter

Owns only AgentScope concerns:

- Build or reuse `HarnessAgent`.
- Resolve the effective model.
- Build `RuntimeContext`.
- Convert ArtVerse `AgentMessage` to AgentScope messages.
- Register task-specific toolsets.

It should not own run persistence, SSE emission, final reply saving, or business retry/degraded semantics.

### Manga Agent Execution

Owns one logical agent execution:

- Build conversation history.
- Sync workspace knowledge.
- Invoke the AgentScope adapter.
- Convert AgentScope events into ArtVerse events.
- Return a final reply or structured execution outcome.

### Run Coordination

Owns durable run lifecycle:

- `RUNNING`, `WAITING_USER`, terminal transitions.
- Idempotency and rate-limit guard calls.
- Cancellation and stale-run repair.
- Restoring open runs.

### Tool Layer

Owns business operations available to the agent:

- Read chapter context.
- Generate/save storyboard scenes.
- Ask the user for a blocking decision.
- Audit tool calls and mark mutating tool success.

Tool implementations should receive per-call business identity from `RuntimeContext`, using a typed value such as `MangaAgentRuntimeContext`.

## Migration Phases

### Phase 1: RuntimeContext-based tool context

Status: implemented first because it is low risk and covered by existing tests.

- Add `MangaAgentRuntimeContext`.
- Put it into `RuntimeContext` in `AgentScopeHarnessAgentGateway`.
- Register Manga Director tools without binding user/chapter/key in the factory.
- Keep compatibility overloads for direct unit tests and non-AgentScope callers.
- Fix mojibake in touched Manga Agent status strings and tests.

Expected behavior should not change.

### Phase 2: Split AgentScope construction

- Extract `AgentScopeRuntimeContextFactory`.
- Extract `MangaDirectorAgentFactory` or `AgentScopeAgentFactory`.
- Move cache-key construction next to agent construction.
- Rename `PROMPT_VERSION` to `MANGA_DIRECTOR_PROMPT_VERSION` and bump it when prompt semantics change.
- Add focused tests for context construction and tool registration.

### Phase 3: Split run execution from run coordination

- Extract `MangaAgentExecutionService` for sync and streaming AgentScope execution.
- Extract a small result type for reply, degraded flag, and terminal/cancelled outcome.
- Keep `MangaAgentService` as the public application facade for controllers.
- Preserve existing API contracts while reducing method size and parameter passing.

### Phase 4: HITL v2 alignment

- Keep `ask_user` as the business-facing tool name unless the frontend contract changes.
- Evaluate replacing manual `ToolSuspendException` flow with AgentScope v2 external execution or permission events.
- Model user input request and resume payloads as explicit AgentScope events where possible.
- Update AG-UI mapping to reflect native pause/resume events.

### Phase 5: Production state and workspace hardening

- Review whether the default file state store is acceptable for deployment.
- Consider Redis or database-backed `AgentStateStore` for multi-instance deployments.
- Keep workspace knowledge and AgentState separate.
- Audit shell/filesystem tool policy for each business agent.

## Validation Strategy

For backend changes:

```bash
cd ArtVerse
mvn -q -DskipTests compile
mvn -q -Dtest=AgentScopeHarnessAgentGatewayTest,MangaAgentToolFactoryTest test
```

For frontend or AG-UI contract changes:

```bash
cd frontend
npm run build
```

## Knowledge Hygiene

When a phase changes behavior, update:

- `docs/knowledge/modules/manga-agent/SKILL.md`
- `docs/knowledge/modules/manga-agent/flow.md`
- Related backend tests
- `frontend/src/api.ts` and `frontend/src/components/MangaAgentPage.tsx` for protocol changes

