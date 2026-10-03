<!-- moved-from: AGENTS.md L413-L421 sha256=88b6444718adeec2452897f6a573481b2c4029e6fcdd1dde6feaa16a3f7d5cc0 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-COOP-VERIFY-RAILS -->
## Cooperative verification rails (multi-agent deferred verify)

- `scripts/coop_verify.py` is the shared deferred-verification rail layered on the existing journal/lease/checkpoint layer (`data/agent-handoff/coop-verify/` store). Skill: `.agents/skills/awx-cooperative-verification/SKILL.md`.
- Another writer editing a ticket scope -> `DEFERRED` + ticket - never `PASS`. Own end-of-turn state is `APPLIED_PENDING_VERIFICATION`.
- Wrappers call `coop_verify writer-begin|heartbeat|end` around real edits and `request`/`run-once`/`watch` for verification. `run-once` exits 10 DEFERRED / 0 PASS / 20 FAILED / 11 INVALIDATED / 30 ERROR.
- Before `begin`/verification: `status`/`recover` first. Orphan writer -> `BLOCKED_UNKNOWN_OWNER` - never silent-reclaim or verify-as-PASS.
- Receipts separate source/built/running/on-glasses evidence. No new busy.lock sole-truth, no state server, no product Java/RAG changes. Contract `DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928`; Codex-side hook/wrapper wiring per `docs/diagnostics/coop-verify-0928/FOR_CODEX.md`.
<!-- END DEMO1-COOP-VERIFY-RAILS -->
