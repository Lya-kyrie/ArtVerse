---
name: api-layer
description: REST controllers, DTOs, SSE streaming endpoints — all HTTP-layer entry points
---

# API Layer

REST controllers (18 controllers) and DTOs. All `/api/**` routes.

## Code Map

| Class | Package | Role |
|-------|---------|------|
| `AuthController` | `api` | Login, register, logout, refresh, kickout, /me |
| `ChapterController` | `api` | Chapter CRUD, content source management |
| `StoryController` | `api` | Story CRUD, workspace management, typed publication requests |
| `MangaAgentController` | `api` | Manga agent chat, SSE streams, resume, cancel |
| `MangaGenerationController` | `api` | Manga image generation, SSE progress, stats |
| `ImageGenController` | `api` | Standalone image generation |
| `ChatController` | `api` | AI chat (novel writing assistant) |
| `NovelController` | `api` | Novel content import |
| `StoryboardController` | `api` | Storyboard scene CRUD |
| `CharacterController` | `api` | Character profile management |
| `AssetGroupController` | `api` | Story asset group management |
| `ReferenceImageController` | `api` | Reference image upload/delete (MinIO) |
| `SquareController` | `api` | Public content discovery |
| `WorksController` | `api` | User's published works |
| `ExportImportController` | `api` | Story export/import as ZIP |
| `UserController` | `api` | User profile, API key management |
| `StaticMediaController` | `api` | Static media file serving |
| `GuardStatsController` | `api` | Internal guard metrics (admin) |
| `MangaAgentDtos` | `api/dto` | Agent request/response DTOs |
| `AuthDtos` | `api/dto` | Auth request/response DTOs |
| `StoryDtos` | `api/dto` | Story publication request contract |
| `ApiDtos` | `api/dto` | Shared DTOs |

## Key Patterns

- **SSE streaming**: `MangaAgentController` and `MangaGenerationController` return `SseEmitter` directly. Background work runs on `mangaGenerationExecutor` (virtual threads).
- **Auth**: `SaTokenConfig` interceptor protects `/api/**` except `/api/auth/**`, `/api/square/**`, `/static/**`, `/actuator/health`.
- **Provider secrets**: `GET /api/user/provider-configs/{configId}/api-key` is authenticated, checks profile ownership in `ApiKeyService`, and returns `Cache-Control: no-store`.
- **Thin controllers**: Controllers resolve user, validate, then delegate to services. No business logic in controllers.
- **Story publication contract**: `PUT /api/stories/{id}/publish` binds `is_published`, `format`, and `chapter_ids` through `StoryDtos.PublishRequest`; the DTO converts the external format to `PublicationFormat` before delegation.
- **Standalone image generation**: `POST /api/image-gen/generate` creates a durable `RUNNING` record and returns it immediately; `GET /api/image-gen/history` is the recovery/status endpoint.
- **Static media caching**: successful `/static/manga/**` responses use a private one-year immutable browser cache because stored media paths contain unique filenames; failed responses use `no-store` so transient storage failures are retryable.
- **AG-UI protocol**: Manga agent streaming uses AG-UI event frames as default SSE protocol.

## Invariants

- Controllers must not contain business logic — delegate to `MangaAgentService`, `MangaGenerationService`, etc.
- SSE emitters must complete or error exactly once. Use `sink.complete()` in `finally` blocks.
- `requestId` is the idempotency key across all streaming endpoints.
- New endpoints must be added to `SaTokenConfig` interceptor exclude list if public.
- Knowledge-base endpoints are protected under `/api/stories/{storyId}/knowledge`; controllers delegate ownership checks, structured-field validation, indexing, and recall to `KnowledgeService`.
- Story publication defaults a missing `format` to `manga`; unsupported values return `400` with `Invalid publication format`.
