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
- **Streaming event filtering**: When using `HarnessAgent.stream()`, filter out `EventType.AGENT_RESULT` AND `Event.isLast()` events to avoid duplicating the full response text. AgentScope emits incremental REASONING deltas followed by a final full-text event with `isLast()=true` — only forward non-last REASONING events. The correct filter chain is: `.filter(e -> e.getType() != EventType.AGENT_RESULT && !e.isLast() && e.getMessage() != null && e.getMessage().getTextContent() != null)`.
- **SSE token format**: Always wrap SSE token data in JSON: `.data(objectMapper.writeValueAsString(Map.of("content", token)))`. The frontend parses `data.content` from JSON — sending raw strings silently fails during `JSON.parse()` and tokens are dropped. Only `done`/`error` events with proper JSON work without this.
- **Dotenv-java 3.x gotcha**: `Dotenv.get()` checks system environment variables FIRST, then `.env` file entries. If a system env var `DEEPSEEK_API_KEY` exists, it takes priority over `.env`. Workaround: read `.env` file directly via `Files.lines()` and only fall back to `dotenv.get()`.

## Recent Fixes (2026-05-29)

1. **MangaPanel.tsx crash** - Fixed null safety issue with `existingImages` computation
2. **ChapterDto missing story_id** - Added `storyId` field to DTO
3. **AssetGroup API format** - Fixed response format to match frontend expectations (`groups`, `max`, `selected_group_id`)
4. **Code review fixes** (2026-05-29):
   - Gallery/lightbox counter: `imageCount` → `activeImageCount` during generation
   - Removed duplicate `onChapterRefresh` in manga generation SSE handler
   - `liveErrorMsg`: `||` → `??` for proper empty-string handling
   - `ChapterDto.from()`: Added `safeStoryId()` for lazy proxy safety
5. **PUT image-count 500 error** (2026-05-29):
   - Root cause 1: `@RequestBody Map<String, Integer>` — Jackson parses JSON integers as `Long`, causing silent deserialization failure
   - Fix: Changed to `Map<String, Object>` + `((Number) val).intValue()`
   - Root cause 2: DB CHECK constraint `image_count IN (4,6,8,10,12,15,20)` — invalid values caused `DataIntegrityViolationException` → 500
   - Fix: Added `ALLOWED_IMAGE_COUNTS` validation in `ChapterService.updateImageCount()` returning 400 with clear message
   - Frontend: `api.ts` `getImageCount()` — `||` → `??` for nullish coalescing
6. **Chat endpoint field name mismatch** (2026-05-29):
   - Frontend `chatStream()` sent `{ content }` but backend `ChatController` expected `{ message }`
   - Fix: Changed frontend to `JSON.stringify({ message: content })`
   - Impact: Chat messages were silently lost (always `null` on backend)
7. **Scenes update request format mismatch** (2026-05-29):
   - Frontend `updateScenes()` sent `{ scenes: [...] }` but backend `StoryboardController` expected bare `[...]`
   - Fix: Changed frontend to `JSON.stringify(scenes)` (bare array)
   - Impact: Scene updates always failed with deserialization error
8. **Cover upload field name mismatch** (2026-05-29):
   - Frontend sent `{ image: base64 }` but backend expected `{ cover_image: base64 }`
   - Fix: Changed frontend to `JSON.stringify({ cover_image: base64 })`
   - Impact: Cover upload silently set `cover_image` to `null`
9. **Import story Content-Type mismatch** (2026-05-29):
   - Frontend sent raw binary with `Content-Type: application/zip` but backend expected `multipart/form-data`
   - Fix: Changed frontend to use `FormData` with named `file` field
   - Impact: Import endpoint always returned 400
10. **Asset group API path mismatch** (2026-05-29):
    - Frontend used `/api/stories/{storyId}/asset-groups/{groupId}` but backend used `/api/asset-groups/{groupId}`
    - Fix: Changed frontend paths to match backend
11. **Asset group response format mismatch** (2026-05-29):
    - Frontend expected `{ group, groups }` wrapper but backend returned bare entity
    - Fix: Frontend now re-fetches group list after create/update/delete
12. **StoryAssetGroup lazy proxy crash** (2026-05-29):
    - `StoryAssetGroup.story` was `LAZY` fetch, causing `LazyInitializationException` during JSON serialization with `open-in-view: false`
    - Fix: Added `@JsonIgnore` to `story` field (same pattern as `Chapter.story`)
13. **Manga SSE event field name mismatches** (2026-05-29):
    - Progress: frontend read `event.data.current`, backend sent `image_number`
    - Error: frontend read `event.data.error`, backend sent `detail`
    - Fix: Frontend now reads `event.data.image_number` and `event.data.detail`
14. **`setAssetGroup` 500 error — internal method call bypasses `@Transactional` proxy** (2026-05-29):
    - `ChapterController.setAssetGroup()` called `getAssetGroup()` internally, which accessed lazy-loaded `chapter.getStory().getAssetGroups()`
    - Internal calls bypass Spring's proxy, so `@Transactional(readOnly = true)` on `getAssetGroup()` was not applied — Hibernate session was closed
    - Fix: Added `@Transactional` to `setAssetGroup()` controller method to keep session open for the internal call
15. **Ref-images response format mismatch** (2026-05-29):
    - Frontend expected `{ images: [{ filename, image_path, size_kb }], max, source }` but backend returned `{ images: [{ path, url }], source }`
    - Fix: Changed `ReferenceImageController` to return `filename`, `image_path`, `size_kb` fields and include `max` field
    - Impact: Ref images list showed empty or crashed on missing fields
16. **AgentScope Harness refactoring** (2026-05-29):
    - Replaced all custom AI code (`DeepSeekClient`, `WebClientDeepSeekClient`, `DeepSeekModelAdapter`, `DeepSeekHarnessAgentGateway`, `AiMessage`) with `io.agentscope:agentscope-harness:1.1.0-RC2`
    - `OpenAIChatModel` configured for DeepSeek (OpenAI-compatible) in `AgentScopeConfig`
    - `AgentScopeHarnessAgentGateway` uses `HarnessAgent` with per-story caching, workspace context, and message compaction
    - Image generation (`Image2Client`) kept separate — not replaced by AgentScope
    - Dependency: `io.agentscope:agentscope-harness:1.1.0-RC2` + `io.github.cdimascio:dotenv-java:3.2.0`
    - Removed: `io.agentscope:agentscope-spring-boot-starter`, `com.openai:openai-java`
17. **AgentScope stream text duplication** (2026-05-29):
    - `HarnessAgent.stream()` emits incremental REASONING deltas followed by a final full-text event with `isLast()=true`, plus an AGENT_RESULT event
    - Filtering only `AGENT_RESULT` wasn't enough — the last REASONING event (`isLast()=true`) also contains the full response text
    - Fix: Added `&& !e.isLast()` to the stream filter in `AgentScopeHarnessAgentGateway.streamChat()`
    - Impact: Every chat response was duplicated (accumulated deltas + full text from last event)
18. **Chat SSE token format — frontend drops streaming tokens** (2026-05-29):
    - Backend sent raw strings as SSE `token` data, but frontend `ChatPanel` does `JSON.parse(dataStr)` and reads `data.content`
    - `JSON.parse("嗨")` throws `SyntaxError`, silently caught by try-catch — all streaming tokens were dropped
    - User only saw the final response from the `done` event (which uses proper JSON `{"content":"..."}`)
    - Fix: Changed `ChatService` to send `{"content": token}` JSON in token events
    - Impact: Real-time character-by-character streaming was completely broken; users only saw the full response at completion
19. **dotenv-java 3.x system env var priority** (2026-05-29):
    - `Dotenv.get("DEEPSEEK_API_KEY")` returns system environment variable value over `.env` file value in dotenv-java 3.x
    - User had an old/invalid `DEEPSEEK_API_KEY` system env var ending in `5fbb`; `.env` file had the correct key ending in `8123`
    - Backend was using the old system env var, causing DeepSeek 401 authentication errors
    - Fix: Added `readFromEnvFile()` in `AgentScopeConfig` that reads `.env` directly via `Files.lines()`, bypassing dotenv-java's `get()`
    - Added masked API key logging for debugging: `sk-fcd0****8123`
20. **`.env` file and `.gitignore`** (2026-05-29):
    - Created `ArtVerse/.env` template with `DEEPSEEK_API_KEY`, `IMAGE_API_KEY`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`
    - Created root `.gitignore` to prevent `.env` from being committed
