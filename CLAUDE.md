# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Big picture
ArtVerse is a full-stack manga/story creation app. The backend is the system of record; the frontend is a thin SPA shell over backend APIs and SSE/AG-UI streams. Changes to AI workflows usually span backend application services, agent/workflow integration, and a small React client layer.

### Backend
- `ArtVerse/` is a Spring Boot 3.3 / Java 21 Maven app.
- HTTP entrypoints live in `ArtVerse/src/main/java/com/artverse/api`; keep controllers thin and delegate into services.
- Core business logic lives in `ArtVerse/src/main/java/com/artverse/application`.
- Guarding, idempotency, concurrency control, and related metrics live in `ArtVerse/src/main/java/com/artverse/guard`.
- Storage adapters, config, AOP, exceptions, and other infrastructure live under `storage`, `config`, `common`, and related packages.
- Flyway migrations live in `ArtVerse/src/main/resources/db/migration`; runtime configuration is in `ArtVerse/src/main/resources/application.yml`.
- `ArtVerse/AGENTS.md` says manga-agent and workflow changes should start from `docs/knowledge/INDEX.md` and the relevant module `SKILL.md`; keep those docs aligned with code when they diverge.
- Local AgentScope sources live in `D:\develop\workspace\io\agentscope`.
- `D:\develop\workspace` is the local Maven checkout when you need to inspect or build AgentScope dependencies.

### Frontend
- `frontend/` is a Vite + React 19 + TypeScript app.
- `frontend/src/App.tsx` is the navigation shell and state coordinator for auth, story/chapter selection, and page switching.
- `frontend/src/api.ts` is the central client for REST, upload, auth refresh, and SSE/AG-UI stream handling.
- `frontend/src/components/` contains the page-level panels and screens.
- There is no frontend test script configured; build and lint are the main frontend checks.

## Common commands

### Local services
```bash
cd ArtVerse
docker compose up -d
```

### Backend
```bash
cd ArtVerse
mvn spring-boot:run
mvn -q -DskipTests compile
mvn test
mvn -Dtest=AuthServiceTest test
mvn -Dtest=AuthServiceTest#someMethod test
```

### Frontend
```bash
cd frontend
npm install
npm run dev
npm run build
npm run lint
npm run preview
```

## Read these first for AI workflow changes
For changes in manga-agent, workflow-engine, agent-integration, guard, or AG-UI/SSE behavior, read:
- `docs/knowledge/INDEX.md`
- the relevant `docs/knowledge/modules/*/SKILL.md`
- the matching `REFERENCE.md` when you need call-graph or dependency detail

If the knowledge files disagree with code, trust the code and update the docs in the same change when practical.
