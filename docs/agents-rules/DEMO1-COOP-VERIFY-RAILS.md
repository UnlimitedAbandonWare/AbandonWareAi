<!-- moved-from: AGENTS.md L421-L429 sha256=88b6444718adeec2452897f6a573481b2c4029e6fcdd1dde6feaa16a3f7d5cc0 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-COOP-VERIFY-RAILS -->
## Cooperative verification rails (multi-agent deferred verify)

- `scripts/coop_verify.py` is the shared deferred-verification rail layered on the existing journal/lease/checkpoint layer (`data/agent-handoff/coop-verify/` store). Skill: `.agents/skills/awx-cooperative-verification/SKILL.md`.
- Another writer editing a ticket scope -> `DEFERRED` + ticket - never `PASS`. Own end-of-turn state is `APPLIED_PENDING_VERIFICATION`.
- Wrappers call `coop_verify writer-begin|heartbeat|end` around real edits and `request`/`run-once`/`watch` for verification. `run-once` exits 0 VERIFIED_PASS / 10 DEFERRED·QUIESCING / 11 INVALIDATED / 20 FAILED / 21 ENVIRONMENT_ERROR·TIMEOUT / 30 BLOCKED_UNKNOWN_OWNER / 44 NO_PENDING_TICKET (scripts/coop_verify.py EXIT_*). exit 0은 그 티켓만 통과이며 목표 전체 완료가 아니다.
- Before `begin`/verification: `status`/`recover` first. Orphan writer -> `BLOCKED_UNKNOWN_OWNER` - never silent-reclaim or verify-as-PASS.
- Receipts separate source/built/running/on-glasses evidence. No new busy.lock sole-truth, no state server, no product Java/RAG changes. Contract `DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928`; Codex-side hook/wrapper wiring per `docs/diagnostics/coop-verify-0928/FOR_CODEX.md`.
<!-- END DEMO1-COOP-VERIFY-RAILS -->
