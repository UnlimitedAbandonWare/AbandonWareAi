# Dynamic RAG Orchestration Platform — Project Memory (canonical)

> Source beats memory. Current files, build/test/runtime evidence, and
> `docs/PROJECT_STATUS.md` always outrank this file and any agent-local memory.

## Status source order

1. `docs/PROJECT_STATUS.md` — only overall-status entry point.
2. Live files under `main/java`, `main/resources`, `app/src/main/java_clean`,
   `app/src/main/resources`, plus configs actually loaded by Gradle/Spring.
3. Focused verification output for the touched seam.
4. This memory file and agent-local sessions — hints only, never proof.

## Current verified facts (2026-09-19)

- Project root is exactly `C:\AbandonWare\demo-1\demo-1\src`.
- Anonymous-first: `demo.interview.enabled=false` everywhere.
- Ports: server `18180`, management `18181`, netty `18182`; profile `local,meta-display`.
- Lens contract: `conversation + hint + hintId + hintExpiresAt + display`.
- Hint lifetime paging policy lives in `application-meta-display.yml`.
- Ollama model choice follows `configs/api-routing.yaml` and `docs/API_ROUTING_SPEC.md`.
- Git history is not a restore baseline; recovery comes from task checkpoint preimages.

## Agent-shared rules

- Register file-changing work with `scripts/work_journal.py`.
- Preserve per-change bytes with `scripts/codex_work_checkpoint.py`.
- Keep one source-owner lease per target; never delete another owner's lock.
- Redact secrets; use environment names only.
- Never invent models, search results, citations, or verification.

## Memory lifecycle

- Add only design decisions, currently valid environment info, and verified fixes.
- Each durable entry needs evidence path plus checked date.
- Failed, duplicate, stale-assumption, and superseded entries are removed.
- Agent runtimes may cache this file locally, but must reread the repo copy at task start.
