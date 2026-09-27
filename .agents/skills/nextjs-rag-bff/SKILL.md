---
name: nextjs-rag-bff
description: Design or implement a Next.js App Router frontend/BFF for the Dynamic RAG
---

# Next.js RAG BFF

## Core Rules

- Inspect the actual Next.js project before coding. Do not assume the version from the request matches `package.json`.
- If the installed project says to read local Next docs under `node_modules/next/dist/docs/`, do that before editing.
- Keep the Spring Boot RAG backend authoritative for search, memory, LLM routing, provider keys, and safety gates.
- Use Next.js as UI/BFF: session handling, API proxy, streaming relay, dashboards, and UX state.
- Do not expose provider keys, owner tokens, admin tokens, raw trace dumps, or backend-only config to browser code.
- Preserve `X-Session-Id` and `X-Request-Id` across browser, Next route handler, and Spring Boot.
- For streaming chat, prefer a Route Handler that relays backend SSE/stream bytes with backpressure rather than buffering the whole response.

## Workflow

1. Read `references/integration-map.md`.
2. Verify local setup:
   - `Get-Content package.json`
   - `npm.cmd run build` or `npm.cmd run lint` only after checking scripts
   - inspect `src/app`, `next.config.*`, and local `AGENTS.md`
3. Choose integration mode:
   - BFF proxy: browser calls Next `/api/*`; Next calls Spring Boot.
   - Direct same-origin reverse proxy: production web server maps `/api/*` to Spring Boot and Next serves UI.
   - Static dashboard: Next only consumes public read-only diagnostics.
4. Keep server URLs in server-only env vars such as `RAG_BACKEND_URL`. Never put secrets in `NEXT_PUBLIC_*`.
5. Implement API route handlers under `src/app/api/.../route.ts`; avoid Pages Router unless the project already uses it.
6. Keep client components focused on UI state and streaming display. Keep backend calls in Route Handlers or Server Components.
7. Verify with browser-level smoke after frontend changes: chat send, SSE receive, reconnect/cancel, and diagnostics page render.

## Backend Contracts To Preserve

- Chat stream: backend `/api/chat/stream` or current active stream endpoint.
- Sync chat: backend `/api/chat/sync` or current active sync endpoint.
- RAG diagnostics: backend `/api/rag/**`, `/api/diagnostics/**`, debug event endpoints if enabled.
- Mode flags such as Brave/RuleBreak must be explicit headers or request fields and must be audited by the backend.

