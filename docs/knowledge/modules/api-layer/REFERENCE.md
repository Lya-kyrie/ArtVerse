# API Layer — Call Graph

## Called By (inbound)

```
Frontend (React SPA)
  → AuthController          (login, register, logout, refresh)
  → ChapterController       (chapter CRUD)
  → StoryController         (story CRUD)
  → MangaAgentController    (agent chat, SSE streams)
  → MangaGenerationController (manga gen, SSE progress)
  → ImageGenController      (image generation)
  → ChatController          (AI chat)
  → NovelController         (novel import)
  → StoryboardController    (storyboard CRUD)
  → CharacterController     (character profiles)
  → AssetGroupController    (asset groups)
  → ReferenceImageController (ref image upload)
  → SquareController        (public content)
  → WorksController         (user works)
  → ExportImportController  (ZIP export/import)
  → UserController          (profile, API keys)
  → StaticMediaController   (static files)
  → GuardStatsController    (internal metrics)
```

## Calls Into (outbound)

### Story publication request

```text
PUT /api/stories/{id}/publish
  -> StoryDtos.PublishRequest
     { is_published, format, chapter_ids }
  -> PublicationFormat
  -> StoryService.publish
```

```
api controllers
  → application.*         (business logic delegation)
  → application.workflow  (agent routing)
  → domain.*              (entities for request/response mapping)
  → persistence.*         (lookups for current user resolution)
  → guard.*               (rate limiting via @RateLimit)
  → common.*              (BusinessException, GlobalExceptionHandler)
  → storage.*             (MinIO for image upload)
  → media.*               (media path resolution)
```

## Key Dependencies

| Dependency | Purpose |
|-----------|---------|
| `MangaAgentService` | Agent chat SSE orchestration |
| `MangaGenerationService` | Manga image generation SSE |
| `AuthService` | Login/register business logic |
| `ChapterService`, `StoryService` | CRUD operations |
| `GenerationGuardService` | Idempotency checks via `@SingleFlight` |
| `ObjectStorageService` | MinIO file upload/download |
| `CurrentUserService` | Resolve current user from Sa-Token session |
| `ApiKeyService` | User API key management |
