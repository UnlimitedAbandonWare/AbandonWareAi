---
description: Next.js RAG BFF frontend work (loads when touching frontend/).
paths:
  - "frontend/**"
---

# Frontend BFF — verify in its own root

This rule only activates when a request or open file touches `frontend/` (see `paths`).

- Verify inside `frontend/` with `npm run lint` + `npm test`; do not run Gradle for frontend-only changes.
- Design/architecture seams: read `.agents/skills/nextjs-rag-bff/SKILL.md` first.
- Frontend source changes do not reach the Meta Display lens by themselves; lens assets live under `main/resources/static/assets/display/` (see `10-meta-display-runtime.md` when touching those).
