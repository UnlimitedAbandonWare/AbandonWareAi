# demo-1 Grok memory/session contract

Grok CLI project rule — auto-loads when Grok starts in this root (verify with
`grok inspect`; other agents read it like any file). App-side paste mirror:
`agent-prompts/grokbot-memory-session.md`. Change report:
`docs/diagnostics/grokbot-memory-session-improve-0928.md`.
Contract: DEMO1-DEVIN-GROKBOT-MEMORY-SESSION-20260928.

## Active profile facts (durable — mirror one line each)

- Project Root: `<repo>` — auto-created sessions
  included; attachments/Downloads/ZIP dirs are read-only inputs, never the root.
- Sole remote: `AbandonWareAi` — `origin` at that URL only; no other remote.
- Prototype Light / proto-open posture — no admin hardening unless the user
  says "harden".
- Self-Ask verified delivery: outcomes carry file/test/command evidence or are
  marked `evidence_needed` / `NOT_RUN`.
- RTX 3090 power fault RESOLVED 2026-09-24 — local GPU lane is first choice.
- Conditional local Git only: `status`/`diff`, selective `add` of owned paths,
  staged-blob secret scan, one local commit — never push/merge/clean.
- Secrets stay env names; values are never printed, logged, committed, pasted.
- Multi-session coexistence: one live RAG owner per machine; the
  `DEMO1-DEVIN-MULTI-SESSION-BUILD-20260928` header rules apply to Grok too.

## Deprecated episode facts — do NOT re-apply

| Old memory claim | Standing truth |
|---|---|
| "RTX 3090 needs PL80 / power-limit mitigation" | RESOLVED 2026-09-24 (power feed). 3090 is the preferred local lane. |
| "An old/discarded repository is a remote to fetch/push" | Discarded. Sole remote is AbandonWareAi; report leftover references, never use them. |
| "API embedding is the default to spare the GPU" | Stale pre-fix workaround. Local Ollama embedding first per `configs/api-routing.yaml`; API is routing-spec fallback, not a power default. |

When an old episode conflicts with this table, the table wins. Do not open a
repair task from a deprecated fact — note the conflict to the user instead.

## Write contract — profile vs log vs note

- **profile** (durable): only the "Active profile facts" above, one line each.
  Never write the same fact twice (no duplicate Root/remote entries).
- **log** (episode summary): <=5 bullets per task/day — paths, task/contract
  IDs, verdicts. Never secrets, tokens, or pasted PASTE/directive bodies.
- **note** (volatile): same-day hints only; never promote a note into profile
  without user confirmation.
- `data/agent-handoff/codex-autonomy/*` journals, `docs/PROJECT_STATUS.md`, and
  `%USERPROFILE%\.codex\attachments\goal-objective.md` are input artifacts
  owned by other agents — read them, never index them as Grok memory.

## "What was this chat/Display session?" — existing rail

```powershell
python -B scripts/chat_session_debug_export.py status
python -B scripts/chat_session_debug_export.py list --since-hours 24
python -B scripts/chat_session_debug_export.py export <sessionId|runId>
```

Share only the export path in handoffs; never paste record bodies or tokens.
Meta Display conversation rows come via `scripts/meta_display_db_export.py`
related exports — never open the live H2 file.

## Session health (Grok store)

```powershell
python -B scripts/agent_session_watch.py scan --agent grok --since-hours 72
```

Only `auto`-severity findings may run the read-only diagnostic bundle. Never
delete, move, or quarantine session files — that is evidence destruction, not
cleanup.

## Boundary — Codex-owned product seams

Do not modify `ChatSessionMetaMerger`, `memoryMode` persistence,
`pipelineSnapshot.agentWebSearch`, or `chat.js` search state (Codex R3/M5
lanes). One live RAG/ForceRestart owner only; never restart a foreign JVM.
This contract governs how Grok reads and writes memory/session info — it is
not a product chat-memory patch.
