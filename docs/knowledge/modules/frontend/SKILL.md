---
name: frontend
description: React/TypeScript SPA - Vite + Tailwind CSS 4, AG-UI protocol client, manga agent page state machine
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
| `src/App.tsx` | Root component, auth gate, global navigation, and layout |
| `src/api.ts` | All API calls (`authFetch`), AG-UI stream parsing, `ArtVerseMangaAgentHttpAgent` |
| `src/genStore.ts` | Manga generation state management |
| `src/ErrorBoundary.tsx` | React error boundary |

### Components

| Component | View |
|-----------|------|
| `MangaAgentPage.tsx` | Manga agent chat + execution panel, story/chapter/model selectors, conversation list sidebar |
| `HomePage.tsx` | Story workspace / story list; consumes `createStorySignal` to open the new-story dialog |
| `WorkspaceEditor.tsx` | Story editor shell: chapter navigation, mobile chat/manga tabs, `ChatPanel`, and `MangaPanel` |
| `ChatPanel.tsx` | AI novel-writing chat interface; `onGoToManga` switches the workspace editor to manga view |
| `MangaPanel.tsx` | Generated manga image display |
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

- `credentials: 'same-origin'` on all fetch calls sends httpOnly cookies automatically.
- `authFetch()` auto-calls `/api/auth/refresh` on 401.
- Auth-expiry events return protected views to a safe state and clear workspace local state.

### Run State Machine

- `MangaAgentPage` displays active request id, run status, event timeline, tool activity, cancel, and HITL waiting state.
- Open runs restore from persisted events on refresh/reconnect.
- Final messages sync from `/messages` after `RUN_FINISHED`.
- HITL: page shows selectable options when `user_input_requested` is received.

## Invariants

- Manga Agent execution currently uses the backend `DIRECTOR` route only. Do not add frontend route selector state until another backend route exists.
- Left sidebar must expose conversation history; switching conversation loads its messages and open run.
- The execution panel is the single place for agent progress; avoid competing progress widgets.
- Chinese-only labels in Manga Agent UI.
- `App.tsx` should stay thin: authentication, global routing, and layout. Keep workspace/editor behavior in dedicated components or hooks.

## Recent UI Notes

- `App.tsx` includes a dedicated `settings` view. API settings render as a first-class page on the right content area.
- `HomePage` consumes `createStorySignal` from the root shell to open the new-story dialog.
- `WorkspaceEditor` owns chapter navigation and passes `onGoToManga` to `ChatPanel` for mobile tab switching.
- `ChatPanel.tsx` reads models from every enabled LLM provider and sends the selected provider `config_id` plus model with story-chat requests.
- `ImageGenPage.tsx` reads models from every enabled image provider and sends the selected provider `config_id` plus model with image-generation requests. It restores persisted image-generation records on entry and polls while a record is `RUNNING`.
- `ModelSwitcher.tsx` groups models by provider profile rather than model vendor, so duplicate model names across profiles remain independently routable.
- `ApiSettingsPage.tsx` treats backend `config_id` as the provider-profile identity. During legacy local-storage migration, an ID-less local profile may be matched once to a remote profile only when provider, label, normalized Base URL, and selected models all match; unmatched local drafts remain local until saved.
- Editing a synchronized provider fetches its API key from the ownership-checked reveal endpoint. The plaintext stays in the current editor state and is stripped from browser persistence; subsequent AI requests use `config_id` so the backend owns secret resolution.
- `KnowledgeBasePage.tsx` is opened from the workspace header and manages typed story facts, rebuild submission, and chapter recall previews. `EmbeddingSettingsPanel.tsx` is the only UI for configuring the OpenAI-compatible embedding endpoint; it uses the same list/detail flow as provider settings while intentionally keeping no provider or model defaults.
