# Frontend — Call Graph

## Called By (inbound)

```
Browser (user)
  → App.tsx (routing)
    → MangaAgentPage  (manga agent)
    → HomePage        (workspace)
    → ImageGenPage    (image generation)
    → SquarePage      (public content)
    → MyWorksPage     (user works)
    → LoginPage       (auth)
    → GuardDashboardPage (internal)
```

## Calls Into (outbound)

```
frontend → backend API (via api.ts):
  → /api/auth/login, register, logout, refresh, me
  → /api/chapters/{id}/manga-agent/conversations/.../ag-ui/run
  → /api/chapters/{id}/manga-agent/conversations/.../ag-ui/runs/{id}/resume
  → /api/chapters/{id}/manga-generation/generate
  → /api/stories/...
  → /api/characters/...
  → /api/square/...
  → /api/works/...
  → /api/image-gen/...
  → /api/guard/... (internal)
```

## Key Dependencies

| Dependency | Purpose |
|-----------|---------|
| `@ag-ui/core` | AG-UI protocol types |
| `@ag-ui/client` | AG-UI HTTP agent base class |
| `react`, `react-dom` | UI framework |
| `vite` | Build tool |
| `tailwindcss` | CSS framework |
| `api.ts` | Central API layer — all backend communication |
| `genStore.ts` | Client-side manga gen state |
| `imageCache.ts` | Persistent browser cache for generated and reference image Blobs |

## Backend API Contract

The frontend depends on these backend modules:
- `api/MangaAgentController` — agent chat + SSE
- `api/MangaGenerationController` — manga gen + SSE
- `api/AuthController` — login, register, refresh
- `api/StoryController`, `api/ChapterController` — CRUD
- `api/ImageGenController` — image generation

When changing backend API contracts, update `frontend/src/api.ts` and `MangaAgentPage.tsx` together.
