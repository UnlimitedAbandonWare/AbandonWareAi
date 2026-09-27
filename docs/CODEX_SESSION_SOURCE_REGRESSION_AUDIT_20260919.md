# Codex session-to-source regression audit — 2026-09-19

Task: `codex-9quarantine-regression-audit-83f6034b`
Scope: evidence preservation, cause analysis, minimum fix proposal only.
No permanent deletion. No source modification.

## Read-only versus changed in this task

Read-only: Codex sessions, archived 13, memories, `memories_1.sqlite`,
`config.toml`, Cline sessions, Grok sessions/memory, old quarantine manifest
and apply-log, current source files, quarantined copies.

Changed: this report; task-local quarantine evidence; new quarantine payload
plus dedicated log under `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919\`.
No repo source file was modified.

## Current confirmed issues

| Priority | Symptom | Related session/time | Current file/function/lines |
|---|---|---|---|
| P1 | Non-local generation returns 503 `chat_admission_unavailable`; local/demo works | `01a094e1-0550-7e23-983f-5dbd8ee4adc2`, 2026-09-12T09:09:30Z | `ChatGenerationAdmissionFilter.java:48`, `:87-96`, `:120-144`; `UpstashRedisClient.java:47-64`, `:66-88` |
| P2 | Live body-fetch failure silently returns null | `01a09465-736f-7de3-92bb-fe2f12b92bdf`, 2026-09-12 | `PageContentScraper.java:44-87` |
| P3 | Search probe unavailable without enablement plus token | Same search/RAG session as P2 | `SearchProbeController.java:28-65` |

P1 evidence: quarantined session inspected `ChatApiController`, admission filter,
and Redis config and reached the same unavailable-dependency diagnosis. Current
code still fails closed outside demo/local when Redis is unavailable. Likely
environment/config behavior, not proven Codex regression. Local/demo bypass is
at filter lines 47-48 and 87-96.

P2 evidence: quarantined session identified
`PageContentScraper.fetchText(String,int)` as cheapest live seam. Current code
still returns null for timeout/policy/other failure. This is a caller-visibility
limitation, not a confirmed regression.

P3 evidence: current probe returns 404 when disabled and 401 on token mismatch.
Expected safeguard, not a regression.

## Keep as-is

Current Cline running session; Codex archived 13; remaining Codex sessions 2,714;
Grok active sessions and workspace memory; small Claude history; Redis/credential
handling; admission fail-closed default; prior `docs/ai-memory/` files as hints;
old quarantine history as history only.


## Unconfirmed

- Live non-local 503 reproduction; restart/reproduction prohibited this turn.
- Four focused tests: admission, Redis client, scraper, probe. Gradle exceeded
  tool timeout, so all are planned, not passed.
- Full search/RAG/transcription/hint runtime behavior.
- Remaining 2,714 sessions plus archived 13 beyond targeted sampling.
- Parent-thread patch commands beyond child inspection evidence.
- Memory regeneration runtime effect.

## Quarantine evidence

- Before: 2,723 sessions; archived 13.
- After: 2,714 sessions; archived 13.
- Dedicated rescue root plus `apply-9only.jsonl` with 9 `moved` entries.
- Pre/post sha256 matched for all 9.
- Old manifest `a35c9c94...` and apply-log `490b7cad...` preserved task-locally.
- Restore: move each quarantine file back to logged `src`.
- Helper `bounded_apply_9only.py` moves only, never deletes, requires count 9.

Nine isolated child sessions under four parents, all project-root scoped, range
11-15,769 lines. They preserve admission, probe, scraper, MCP, subscription
evidence and must remain analyzable.

## Prior cleanup self-check

- Manifest overwrite: 1,952 entries replaced by 9 on rerun.
- Old apply-log: 1,983 `moved` lines, history only; payload already purged.
- PROJECT_STATUS row overwrite detected and restored.
- Old sealed checkpoint plus `checkpoint-operation-active` hold untouched.
- `ContextPurityScriptContractTest` proves scan/report contracts only.
- Memory remains `generate_memories=false`, `use_memories=false` at file level.
- Stage/job SQLite rows used as residual evidence only.

## Coverage and minimum-fix order

- Fully copied/read in targeted form: 9 quarantined sessions.
- Sampled: current admission, Redis, scraper, probe, configs, memory settings.
- Not fully read: all remaining sessions; full analysis not claimed.
- Deleted/unreadable: prior purged payload.
- Confidence: environment/config admission behavior high; Codex source damage unproven.
- First: P1 decision-only selection among local/demo, provisioned Redis, or scoped
  local admission bypass, with focused tests.
- Second: P2 caller-side null-cause distinction.
- Third: transient probe enablement only for diagnosis.
