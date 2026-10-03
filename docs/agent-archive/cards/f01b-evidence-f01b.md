# F01-B narrow JDBC: 418 tests / 413 pass / 5 baseline fail (new=0), gate-suites 44t/0f, DDL 3건 applied
- card-id: f01b-evidence-f01b
- kind: measured-number
- status: still-true (역사 측정값)
- date: 2026-09-29 KST
- evidence: data/agent-handoff/codex-autonomy/trace-r2-jev-late-0929-933e4528/f01b-evidence-summary.md ; SoT data/agent-handoff/codex-autonomy/f01b-jdbc-implementation-0929-db9db278/
- reverify: `Get-Content data\agent-handoff\codex-autonomy\trace-r2-jev-late-0929-933e4528\f01b-evidence-summary.md`

## 근거
- affectedTests: 418 total / 413 pass / 5 baseline fail (new=0).
- GATE: gate-suites.json 44t/0f; gate0-after-apply·gate0-live-after-restart·
  gate0-receipt-after-apply-retry = GATE0_PASS; gate0-receipt-after-apply = GATE0_FAIL_EVIDENCE_NEEDED.
- DDL 적용 3건 (sha256 기록): V20260912__durable_jobs.sql, V20260912_03__job_idempotency.sql,
  docs/diagnostics/f01b-understanding-receipt-proposal.sql.
- 미검증: liveProviderProof·task_ask·callbacks·InMemory·trace·browserAdmin = NOT_OBSERVED/제한.
