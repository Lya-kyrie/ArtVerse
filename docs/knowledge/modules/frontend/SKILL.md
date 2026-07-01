---
name: frontend
description: React/TypeScript SPA — Vite + Tailwind CSS 4, AG-UI protocol client, manga agent page state machine
---

# Frontend

React/TypeScript SPA (Vite + Tailwind CSS 4). Dev server on port 5173.

## Commands

```bash
cd frontend
npm run dev        # Vite dev server on port 5173
npm run build      # TypeScript check + production build
npm run lint       # ESLint
```

## Code Map

| File | Role |
|------|------|
| `src/main.tsx` | React entry point |
| `src/App.tsx` | Root component, routing: Home → MangaAgentPage, Workspace → HomePage |
| `src/api.ts` | All API calls (`authFetch`), AG-UI stream parsing, `ArtVerseMangaAgentHttpAgent` |
| `src/genStore.ts` | Manga generation state management |
| `src/ErrorBoundary.tsx` | React error boundary |

### Components

| Component | View |
|-----------|------|
| `MangaAgentPage.tsx` | Manga agent chat + execution panel, route selector (自动/聊天/导演/质检/决策), conversation list sidebar |
| `MangaPanel.tsx` | Generated manga image display |
| `ChatPanel.tsx` | AI novel-writing chat interface |
| `HomePage.tsx` | Story workspace / story list |
| `ImageGenPage.tsx` | Standalone image generation UI |
| `ImageEditor.tsx` | Image editing tools |
| `LoginPage.tsx` | Login/register form |
| `SquarePage.tsx` | Public content discovery |
| `MyWorksPage.tsx` | User's published works |
| `GuardDashboardPage.tsx` | Internal guard metrics dashboard |

## Key Protocols

### AG-UI Events
The frontend consumes AG-UI events via `@ag-ui/core` and `@ag-ui/client`. Live events drive the execution panel in `MangaAgentPage`:
- `RUN_STARTED`, `STATE_SNAPSHOT`, `CUSTOM` (tool audit), `TEXT_MESSAGE_CONTENT`, `RUN_FINISHED`, `RUN_ERROR`

### Auth
- `credentials: 'same-origin'` on all fetch calls — sends httpOnly cookie automatically
- `authFetch()` auto-calls `/api/auth/refresh` on 401
- Token never stored in `localStorage`

### Run State Machine
- `MangaAgentPage` displays: active request id, run status, event timeline, tool activity, cancel, HITL waiting state
- Open runs restored from persisted events on refresh/reconnect
- Final messages synced from `/messages` after `RUN_FINISHED`
- HITL: page shows selectable options when `user_input_requested` received

## Invariants

- Route selector must send the selected route with every run. `AUTO` asks backend to classify.
- Left sidebar must expose conversation history — switching conversation loads its messages + open run.
- The execution panel is the single place for agent progress — no competing progress widget.
- Chinese-only labels in Manga Agent UI.

## Recent UI Notes

- `App.tsx` now includes a dedicated `settings` view. API settings no longer open as a modal; they render as a first-class page on the right content area like the other navigation targets.
- `ChatPanel.tsx` reads the configured LLM model list from provider settings and sends the selected model with story-chat requests.
- `ImageGenPage.tsx` reads the configured image model list from provider settings and sends the selected model with image-generation requests.
