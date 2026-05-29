# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ArtVerse is an AI-powered manga/novel generation platform with a Spring Boot backend and React frontend.

## Project Structure

- **Backend**: `D:\develop\Vibe Coding\ArtVerse` (Spring Boot 3.3.5, Java 21)
- **Frontend**: `D:\develop\Vibe Coding\frontend` (React + Vite)

## Tech Stack

- **Backend**: Spring Boot 3.3.5, Java 21, Spring Data JPA, PostgreSQL, Flyway, MinIO
- **Frontend**: React, TypeScript, Vite
- **Database**: PostgreSQL (port 5432)
- **Storage**: MinIO (ports 9000/9001)
- **AI Services**: DeepSeek API via AgentScope Harness (chat/text), Image2 API (image generation)

## AI Architecture (AgentScope Harness)

All text/chat AI uses `io.agentscope:agentscope-harness:1.1.0-RC2`:
- `AgentScopeConfig` creates `OpenAIChatModel` bean (DeepSeek is OpenAI-compatible, base URL: `https://api.deepseek.com`)
- `AgentScopeHarnessAgentGateway` wraps `HarnessAgent` with per-story caching, workspace context, and message compaction
- AI entry point: `HarnessAgentGateway` interface → `streamChat()` / `generateText()`
- Image generation (`Image2Client`) is kept separate — NOT replaced by AgentScope

### Key classes
- `io.agentscope.core.model.OpenAIChatModel` — OpenAI-compatible model adapter (works with DeepSeek)
- `io.agentscope.harness.agent.HarnessAgent` — main agent with workspace, memory, compaction
- `io.agentscope.core.message.Msg` / `MsgRole` — message types
- `io.agentscope.core.agent.RuntimeContext` — per-call session context (sessionId, userId)
- `io.agentscope.harness.agent.memory.compaction.CompactionConfig` — conversation compaction settings

### Deleted files (replaced by AgentScope)
The old AI layer has been removed: `DeepSeekClient.java`, `WebClientDeepSeekClient.java`, `DeepSeekModelAdapter.java`, `DeepSeekHarnessAgentGateway.java`, `AiMessage.java`

## Development Environment

- **Platform**: Windows 11
- **Shell**: Bash (Git Bash or similar)
- **Working Directory**: `D:\develop\Vibe Coding`

## Docker Services

Run `docker-compose up -d` from `D:\develop\Vibe Coding\ArtVerse` to start:
- PostgreSQL: `localhost:5432` (database: `manga_novel`, user: `postgres`, pass: `postgres`)
- MinIO: `localhost:9000` (API), `localhost:9001` (Console, user: `minioadmin`, pass: `minioadmin`)

## Running the Application

### Backend
```bash
cd D:\develop\Vibe Coding\ArtVerse
mvn spring-boot:run
```
Backend runs on `http://localhost:8000`

### Frontend
```bash
cd D:\develop\Vibe Coding\frontend
npm install
npm run dev
```
Frontend runs on `http://localhost:5173`

## Auth

- Spring Security with JWT (stateless). BCrypt for passwords. `AuthController` handles register/login.
- `user_api_keys` table stores encrypted per-user API keys per provider (deepseek/image2). Keys returned masked to frontend.
- `stories.user_id` links stories to owning user. Controllers filter by authenticated user.

## Key Configuration

- `spring.jackson.property-naming-strategy: SNAKE_CASE` - JSON fields use snake_case
- `spring.jpa.open-in-view: false` - Hibernate sessions close before serialization
- DTO pattern used to avoid lazy loading issues

## API Endpoints

- `GET/POST /api/stories` - List/create stories
- `GET/POST /api/stories/{id}/chapters` - List/create chapters
- `GET/PUT/DELETE /api/chapters/{id}` - Chapter CRUD
- `GET/PUT /api/chapters/{id}/color-mode` - Chapter color mode (bw/color)
- `GET/PUT /api/chapters/{id}/image-count` - Chapter image count
- `GET/PUT /api/chapters/{id}/asset-group` - Chapter asset group
- `POST /api/chapters/{id}/chat` - AI chat for chapter
- `POST /api/chapters/{id}/import-novel` - Import novel content
- `POST /api/chapters/{id}/generate-scenes` - Generate scenes from novel
- `POST /api/chapters/{id}/generate-manga` - Generate manga images
- `GET /api/stories/{id}/asset-groups` - List asset groups for story

## Testing

### Unit Tests
```bash
cd D:\develop\Vibe Coding\ArtVerse
mvn test
```

### Frontend-Backend Integration
Use Playwright CLI skill for browser automation testing.

## Tools Available

- **Playwright**: Browser automation and testing skill is configured in `.claude/skills/playwright-cli/`

## Coding Guidelines

### React/TypeScript
- **API contract verification**: When adding or modifying API calls in `api.ts`, always verify the request body field names and format match the backend controller's `@RequestBody` parameter. Common pitfalls: `{ content }` vs `{ message }`, wrapped `{ scenes: [...] }` vs bare `[...]`.
- **API path consistency**: When the frontend calls `/api/stories/{storyId}/asset-groups/{groupId}`, verify the backend route matches exactly. The backend may use a different path like `/api/asset-groups/{groupId}`.
- **Multipart file uploads**: When the backend expects `@RequestParam("file") MultipartFile`, the frontend must use `FormData` with a named field — not raw binary with `Content-Type: application/zip`.
- **SSE event field names**: Always verify the exact field names in SSE event data between backend (`Map.of(...)`) and frontend (`event.data.xxx`). Common mismatches: `image_number` vs `current`, `detail` vs `error`.
- **API response format verification**: When the frontend defines an interface for API responses (e.g. `RefImage { filename, image_path, size_kb }`), verify the backend `Map.of(...)` keys match exactly. Common mismatches: `path` vs `image_path`, `url` vs `filename`, missing `max` field.
- **Derived state consistency**: When computing display values (like counters), always derive from the same source. Don't mix preference values with actual runtime values (e.g. `imageCount` vs `activeImageCount`).
- **Null-safe operator**: Use `??` instead of `||` for values that could be empty strings. `||` treats `""` as falsy and falls through; `??` only treats `null`/`undefined` as nullish.
- **SSE event deduplication**: When handling streaming events, use guard flags to prevent duplicate callback triggers (e.g. don't call `onChapterRefresh` from both the last `image` event and the `done` event).

### Java/Spring Boot
- **Lazy proxy safety**: When accessing lazy-loaded JPA relationships in DTOs (`@ManyToOne(fetch = LAZY)`), always wrap in try-catch with a fallback, consistent with `safeMessages()`/`safeImages()` pattern in `ChapterDto.java`.
- **Lazy entity serialization**: When a JPA entity has `@ManyToOne(fetch = LAZY)` and is returned directly from a controller (not via DTO), add `@JsonIgnore` to the lazy field. Otherwise Jackson triggers `LazyInitializationException` outside the transaction boundary. See `Chapter.story` and `StoryAssetGroup.story` for the correct pattern.
- **open-in-view: false**: Hibernate sessions close after `@Transactional` methods return. DTO mapping must happen within the transaction boundary or handle `LazyInitializationException`.
- **Jackson `@RequestBody` type mapping**: Jackson parses JSON integers as `Long`, not `Integer`. Never use `Map<String, Integer>` as `@RequestBody` — use `Map<String, Object>` and cast with `((Number) val).intValue()`. Otherwise deserialization fails silently with a 500 error.
- **DB CHECK constraints must have service-level validation**: When a database column has a CHECK constraint (e.g. `image_count IN (4, 6, 8, 10, 12, 15, 20)`), always validate in the service layer before `save()` to return a proper 400 error instead of a 500 `DataIntegrityViolationException`.
- **Internal method calls bypass `@Transactional` proxy**: When a controller method calls another `@Transactional` method on the same class (e.g. `setAssetGroup` calling `getAssetGroup`), the call goes directly to the method, not through Spring's proxy. If the called method accesses lazy-loaded JPA relationships, the calling method must also be annotated with `@Transactional` to keep the Hibernate session open.
- **AgentScope Harness usage**: All text/chat AI goes through `HarnessAgentGateway` interface. Use `HarnessAgent.builder()` with `.model(openAIChatModel)`, `.workspace(path)`, `.compaction(config)` to create agents. Build one agent per story (cache by story ID). Use `RuntimeContext.builder().sessionId().userId().build()` for per-call identity. Convert `AgentMessage` to `Msg.builder().role(MsgRole.USER/ASSISTANT/SYSTEM).textContent().build()`.
- **AgentScope model configuration**: `OpenAIChatModel.builder().apiKey().modelName().baseUrl().stream(true).build()`. For DeepSeek, base URL is `https://api.deepseek.com`. API key read from `ArtVerseProperties.deepseek.apiKey` or `DEEPSEEK_API_KEY` env var via Dotenv.
- **Don't create custom adapters**: `OpenAIChatModel` already supports any OpenAI-compatible API. No need to write custom `ChatModel` adapters like the old `DeepSeekModelAdapter`.
- **Streaming event filtering**: When using `HarnessAgent.stream()`, filter out `EventType.AGENT_RESULT` events. For `isLast()` events, use a stateful `AtomicBoolean` to track whether any token has already been emitted — only suppress `isLast()` when prior tokens exist. Unconditional `!e.isLast()` drops single-event short responses (e.g. "好"). The correct pattern: filter non-AGENT_RESULT → use `AtomicBoolean hasEmitted` → skip `isLast()` only if `hasEmitted` is true.
- **SSE token format**: Always wrap SSE token data in JSON: `.data(objectMapper.writeValueAsString(Map.of("content", token)))`. The frontend parses `data.content` from JSON — sending raw strings silently fails during `JSON.parse()` and tokens are dropped. Only `done`/`error` events with proper JSON work without this.
- **Dotenv-java 3.x gotcha**: `Dotenv.get()` checks system environment variables FIRST, then `.env` file entries. If a system env var `DEEPSEEK_API_KEY` exists, it takes priority over `.env`. Workaround: read `.env` file directly via `Files.lines()` and only fall back to `dotenv.get()`.
- **Strip quotes from .env values**: `readFromEnvFile()` should strip surrounding `"` and `'` characters from extracted values. Users commonly copy-paste API keys in `KEY="value"` format from documentation. Unstripped quotes cause silent 401 auth failures.
- **Validate API key at startup**: The AI model bean should log a visible warning if the API key is null/blank after all resolution attempts (properties → .env → dotenv). Don't silently pass null to the model builder.
- **Cancel Reactor subscriptions on SSE disconnect**: Store the `Disposable` returned by `Flux.subscribe()` and call `subscription.dispose()` in `SseEmitter.onTimeout()`, `onError()`, and `onCompletion()` callbacks. Otherwise the AI stream continues generating tokens for a disconnected client, wasting API credits.
- **Remove dead parameters from entire call chain**: When refactoring removes the need for a parameter, remove it from controller → service → gateway. Dead parameters mislead future maintainers into thinking the feature still works.
- **Don't attach API keys to every request header**: `apiHeaders()` should not include auth headers that apply to a subset of endpoints. Attach per-endpoint headers locally to minimize key exposure in logs/proxies/devtools.
- **Wrap reactive `.block()` calls**: When calling `.block()` on `Mono`/`Flux` from non-reactive services, wrap with try-catch and convert library-level exceptions to `BusinessException` with clear user-facing messages (e.g. "AI 服务不可用").

## Recent Fixes Summary (2026-05-29)

Key patterns from 21 fixes:
- **API contract mismatches**: Verify frontend/backend field names, paths, Content-Type, SSE event fields match exactly. Always JSON-encode SSE token data as `{"content": token}`.
- **AgentScope Harness**: Replaced custom AI code. Use `OpenAIChatModel` (OpenAI-compatible) for DeepSeek. Filter `AGENT_RESULT` + use `AtomicBoolean` for `isLast()` events. Cancel `Flux.subscribe()` on SSE disconnect. Strip quotes from `.env` values. Wrap `.block()` in try-catch.
- **JPA/Hibernate**: `open-in-view: false` means sessions close after `@Transactional`. Use `@JsonIgnore` on lazy fields, DTO safe-accessors, and `@Transactional` on methods with detached-entity callbacks. Jackson parses integers as `Long`.
- **Validation**: Always validate DB CHECK constraints in service layer (400, not 500). Warn on missing API keys at startup.
- **Cleanup**: Remove dead parameters from entire call chain (controller→service). Don't leak API keys on every request header. Use `??` not `||` for nullish values. Don't duplicate SSE event callbacks.
