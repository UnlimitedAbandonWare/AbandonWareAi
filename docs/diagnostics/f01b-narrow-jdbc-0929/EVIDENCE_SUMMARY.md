# EVIDENCE_SUMMARY — F01-B narrow JDBC UNDERSTANDING

- contract: `DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`
- post-tools contract: `DEMO1-DEVIN-F01B-POST-TOOLS-20260929`
- generatedAtUtc: 2026-09-29T07:57:28+00:00
- SoT: `C:\AbandonWare\demo-1\demo-1\src\data\agent-handoff\codex-autonomy\f01b-jdbc-implementation-0929-db9db278` (json 86 / files 353)

## RED→GREEN

| slice | red tests | green tests | green fail/err |
|---|---|---|---|
| derived-lost-cancel | — | 14 | 0/0 |
| exact-derived-cancel | — | 32 | 0/0 |
| local-memory | — | 14 | 0/0 |
| payload | — | 4 | 0/0 |
| process-readiness | — | 1 | 0/0 |
| receipt-repository | — | 17 | 0/0 |
| sensitive-approval | 5 | 48 | 0/0 |
| source-boundaries | — | 26 | 0/0 |
| t0-origin | — | 104 | 0/0 |
| t3-handler | — | 61 | 0/0 |
| typed-outcome | — | 66 | 0/0 |
| worker-interrupt | 1 | 15 | 0/0 |
- green-only slices: derived-lost-cancel, exact-derived-cancel, local-memory, payload, process-readiness, receipt-repository, source-boundaries, t0-origin, t3-handler, typed-outcome

추가 런 (페어 아님): controller-baseline=129t/5f, gate=44t/0f, pending-context-real-memory=0t/0f, pending-context-real-memory-repair=59t/0f, receipt-migration-artifact=45t/0f, t0-origin-wiring=48t/0f, t0-origin-wiring-repair=305t/13f

## GATE

| evidence | verdict/tests | failures | exit |
|---|---|---|---|
| gate-suites.json | 44 tests | 0 | — |
| gate0-after-apply.json | GATE0_PASS | — | 0 |
| gate0-live-after-restart.json | GATE0_PASS | — | 0 |
| gate0-receipt-after-apply-retry.json | GATE0_PASS | — | 0 |
| gate0-receipt-after-apply.json | GATE0_FAIL_EVIDENCE_NEEDED | — | 5 |

## DDL 적용 (3건)

| file | statements | sha256 | applied | jdbcExit |
|---|---|---|---|---|
| `main\resources\db\migration\V20260912__durable_jobs.sql` | 2 | b575731f24154875… | True | 0 |
| `main\resources\db\migration\V20260912_03__job_idempotency.sql` | 3 | 784b9c766145cc54… | True | 0 |
| `docs\diagnostics\f01b-understanding-receipt-proposal.sql` | 10 | 837e1b9b03312b09… | True | 0 |

## NOT_RUN / 미검증

| item | reason |
|---|---|
| liveProviderProof | NOT_OBSERVED |
| task_ask | false in limitations |
| callbacks | false in limitations |
| InMemory | false in limitations |
| trace | false in limitations |
| browserAdmin | NOT_OBSERVED |
| commit | false in limitations |
| push | false in limitations |
| fullVerification | target=partial (verify-RAG 범위 외) |
| providerProof | synthetic_only |
| runtimeActivation | default_off_no_flag_flip |

- affectedTests: 418 total / 413 pass / 5 baseline fail (new=0)

NEVER: product java diff 0 유지 / DDL apply 없음 / secrets 출력 없음 / commit·push 없음
