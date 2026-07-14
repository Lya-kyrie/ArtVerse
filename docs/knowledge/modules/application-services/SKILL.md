---
name: application-services
description: Core service layer — auth, chapter, story, character, chat, novel, export/import, API key management
---

# Application Services

Core business logic services excluding agent workflow and manga generation (see separate modules).

## Code Map

| Class | Role |
|-------|------|
| `AuthService` | Registration, login, password validation, BCrypt |
| `ApiKeyService` | Encrypt/decrypt user provider profiles and resolve runtime LLM/image/workflow configuration |
| `ChapterService` | Chapter CRUD, content source management |
| `ChapterAccessService` | Visibility checks (chapter ownership) |
| `StoryService` | Story CRUD, workspace management, publication orchestration |
| `application.publication.*` | Typed manga/novel publication strategies and fail-fast strategy registry |
| `NovelService` | Novel content import, character counting |
| `CharacterProfileService` | Character profile resolution, inheritance |
| `ChatService` | AI novel-writing chat (non-agent) |
| `AssetGroupService` | Story asset group management |
| `WorksService` | Published works management |
| `SquareService` | Public content discovery |
| `ExportImportService` | Story ZIP export/import |
| `CurrentUserService` | Resolve current user from Sa-Token session |
| `RefreshTokenService` | Refresh token lifecycle |
| `ImageGenService` | Standalone image generation |
| `SceneService` | Storyboard scene generation + management |
| `StructuredStoryboardService` | Structured page/panel normalization |
| `MangaImageStorageService` | Manga image persistence to MinIO |

## Key Patterns

- **Service isolation**: Each service is a `@Service` with `@RequiredArgsConstructor`. No circular dependencies.
- **Transaction boundaries**: `@Transactional(readOnly = true)` on query methods, `@Transactional` on mutations. Lazy fields must be extracted before crossing transaction boundaries.
- **Publication dispatch**: HTTP `format` values are parsed to `PublicationFormat` before `StoryService` delegates to `PublicationStrategyRegistry`. Format strategies own only their publication fields; `StoryService` owns authorization, transactions, and persistence.
- **Visibility checks**: `ChapterAccessService.requireVisible()` enforces chapter ownership before mutations.
- **API key encryption**: `ApiKeyService` encrypts user API keys at rest using AES, decrypts on demand.
- **API key reveal**: Provider secrets are returned only by the authenticated, ownership-checked per-profile reveal endpoint and its response is marked `no-store`; provider list responses remain masked.
- **Provider activation**: LLM and image profiles use `active` as an enabled set and may have several active entries. Workflow keeps a single active default.
- **Runtime routing**: Modern AI requests send `config_id` plus one model. The server owns the saved provider, Base URL, and secret; client overrides cannot replace them.

## Invariants

- Services must not depend on API-layer classes (controllers, DTOs).
- `@Transactional` scope must not cross thread boundaries (`executor.submit()`).
- Character profile resolution follows inheritance chain: chapter → asset group → story defaults.
- A disabled provider profile must not be resolved by a runtime request, even when the caller supplies its `config_id`.
- Publication formats default to manga for backward compatibility. Manga and novel publication state must remain independent, and a supplied chapter selection is the complete desired published set for that format.
