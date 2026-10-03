# F01-B narrow JDBC UNDERSTANDING — final evidence

- Contract: `DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`.
- Current user scope: **F01-B only**. TRACE and fresh-admin Browser checks are excluded.
- Root: `C:\AbandonWare\demo-1\demo-1\src`.
- Implementation: **verified for the narrow derived path**. Base defaults remain
  `abandonware.understanding.deferred.enabled=false`, empty `jobs.enabled-types`,
  empty `jobs.callback-enabled-types`. Tests explicitly enable only UNDERSTANDING.
  This report does not claim live B admission or live provider generation.
- Evidence root: `data/agent-handoff/codex-autonomy/f01b-jdbc-implementation-0929-db9db278/`.

## Gate results

| Gate | Result and actual evidence |
|---|---|
| GATE-0 schema | PASS; final read-only auto probe used live HTTP fallback because local H2 was locked; all required jobs/idempotency objects and receipt table present |
| GATE-1 shared TM | PASS; JobConfig uses the application JpaTransactionManager/DataSource; real JPA + JDBC failure injection rolls back both stores |
| GATE-2 eligibility | PASS in synthetic integration; global/request flags, full memory, sensitive/force-off approval, verified owner/session/epoch, exact run and user ID, exact approved Q/A mapping, schema/type readiness |
| GATE-3 T0 | PASS; strict flushed assistant + durable job in one physical transaction; assistant or job insert failure leaves neither new row |
| T2/T3/recovery | PASS; provider outside SQL; checkpoint retry without another provider call; strict USUM + receipt + actual TranslationMemory + success rollback together |
| Runtime freshness | Verify-RAG exit 0 at 2026-09-29 14:58 KST; compile/resources, runtime ownership, HTTP, source freshness and DevWatch passed; target=partial/fullVerification=false; one exceptions warning |
| Live activation | NOT_ENABLED; no runtime configuration flip; F01-A remains the default |

T0 deliberately couples availability: an enqueue failure rolls back that assistant
rather than leaving an origin-only durable success. After admission there is no
synchronous A fallback. Before admission, projection/store mismatch prepares A
outside SQL and outside the transcript gate; a held projection never enqueues the draft.

## Approved local DDL

Only the canonical development H2 `var/meta-display-db/lmsdb` was changed,
observed H2 2.2.224 in MariaDB compatibility mode. No production/shared DB or
existing data DELETE/TRUNCATE was used.

| Applied input | SHA-256 |
|---|---|
| V20260912__durable_jobs.sql | b575731f241548755d59953d28536f08fcf0dd0da8645a17b52fe05f37a023b3 |
| V20260912_03__job_idempotency.sql | 784b9c766145cc54908d3dc8907fd8a10f146bea2df12720b57f5187377984c0 |
| docs/diagnostics/f01b-understanding-receipt-proposal.sql | 837e1b9b03312b09d4f48534c465b79427f4918c3055d8f2d2ab32ae7118ccb5 |

The third approved file applied 10 additive statements: seven job columns,
two job indexes, and the receipt table with its source index. Receipt lifetime
is independent of job TTL; no job FK/cascade was added. Existing migrations are
unchanged. The versioned resource copy
`main/resources/db/migration/V20260929__f01_understanding_receipt.sql`
has the same SQL as the applied proposal (whitespace-normalized equality tested),
SHA-256 `d2bdd10a494b5bc109fe0693351cd476699ffa6d656b8e0b41d02e4149d63b7f`.
It was tested on isolated H2 fixtures and was **not reapplied** to the already migrated DB.
MySQL/MariaDB server compatibility is not claimed by H2 tests.

Applied receipts: `migration-durable-applied.json`,
`migration-idempotency-applied.json`, `migration-receipt-applied.json`.
Fresh gates: `final-schema-gate.json`, `final-schema-gate-postboot.json`; full receipt columns: `receipt-live-schema.json`.
The first overlapping file-H2 probe returned observation-unavailable exit 5;
the sequential retry and final live probe passed. No absent-schema verdict was inferred from a lock.

## Acceptance coverage

| Contract requirement | Fresh proof |
|---|---|
| 1 Origin + intent atomicity | T0 assistant-insert and job-insert failure tests |
| 2 Restart recovery | JdbcJobF01DerivedTest committed-intent restart; separate JVM legacy durability fixture |
| 3 USUM + receipt atomicity | Real JPA/JDBC failures before receipt and before success |
| 4 Two-worker race | Two concurrent computes, one USUM/receipt/local effect |
| 5 Prepared checkpoint | COMMIT retry/restart with provider invocation count unchanged |
| 6 Receipt after job expiry | Job/result cleanup plus replay causes no repeated effect |
| 7 Lease loss | Token/lease fences; compulsory engine end-of-transaction success confirmation |
| 8 Physical cancellation | CANCEL_REQUESTED/unknown waits for local handler return; no claim of remote provider abort |
| 9 Source mutation | Both message fingerprint equalities, edit/delete/owner/consent matrix; mutation during compute and before T0 |
| 10 Typed outcome | PROVIDER / HEURISTIC_FALLBACK / SKIPPED / FAILURE remain distinct |
| 11 task_ask/callback off | Default-deny matrix and no callback path in the derived handler |
| 12 Internal access | Public task status/result/cancel rejects internal type and foreign ownership |
| 13 No origin regeneration | Concrete handler only calls understanding; engine bounded-compute tests; no ChatService dependency |
| 14 No stale SSE | Handler has no emitter/run creation; pending-job stream test emits no understanding event |
| 15 Next-turn context | Both stream and sync preserve transcript, rolling summary and settings while jobs remain PENDING |
| 16 F01-A baseline | Existing preparation/commit and finalized-memory tests remain green; closed F02/Autograde B unchanged |

Additional adversarial defects were reproduced and fixed: missing sensitive-memory
approval in the immutable payload, voluntary handler transaction fences, and stale
maintenance interrupts reaching a reused worker thread. A separate JVM test also
exposed an empty readiness-file race; atomic publication corrected the fixture.
No duplicate queue/service layer or vector side-effect was introduced.

## Fresh verification

Final affected-filter Gradle command: **418 tests, 413 passed, 5 failures,
0 errors, 0 skipped; exit 1**. All five failure identifiers and messages match
the pre-T0 baseline exactly; comparison exit 0, new failures = 0.

1. ChatApiControllerInputGuardTest.legacyUiAcknowledgesRenderedFinalAndRestoresPersistedRunWithoutReplayBubble
2. ChatApiControllerInputGuardTest.syncMetadataExtractionCarriesBoundOwnerAndNeverUsesOwnerlessOverload
3. ChatApiControllerTraceMetaTest.chatApiControllerScannerSamplesUseNamedSuppressionStages
4. ChatApiControllerUtf8StreamContractTest.attachmentFailureSummaryIsCountOnlyAndExactWithoutChangingAnswer [2] total=2, loaded=0
5. The same attachment test [3] total=2, loaded=1

Evidence: `final-affected-tests-rerun.log`, `final-affected-tests-summary.json`,
`final-baseline-comparison.log`, and the pre-T0 `controller-baseline-suites.json`.
These five remain unresolved outside this F01-B scope. The affected command is
not a full pass.

Fresh dev startup: `final-start-result.json`, run
`20260929-145120-c07edf47`, status=ready, springReused=false.
`final-runtime-verify.json` and `final-verify-rag.log` record Verify-RAG exit 0;
`final-compile.log` records compileJava/processResources success.
Runtime source freshness=current, DevWatch=armed, owner identity matched.
The exceptions check warned about already-existing indexes/constraints from
Hibernate DDL (132 matched exception lines); no fatal classpath/Spring/config/port
class was detected. These warnings were not fixed or treated as a clean log.
Verify-RAG reports `target=partial`, `fullVerification=false`: effective config
binding and unit tests are not proven by that command. The Java tests above are
separate evidence. The post-boot read-only probe
`final-schema-gate-postboot.json` again reports GATE0_PASS, complete, exit 0.

Focused green artifacts include:
- `t0-origin-green-suites.json`: 104 tests, 0 failures/errors/skips.
- `pending-context-real-memory-repair-suites.json`: 59 tests, 0 failures/errors/skips.
- `receipt-migration-artifact-suites.json`: 45 tests, 0 failures/errors/skips.
- `worker-interrupt-green-suites.json`: 15 tests, 0 failures/errors/skips.
- `exact-derived-cancel-green-suites.json`: 32 tests, 0 failures/errors/skips.
- `process-readiness-green-suites.json`: 1 separate-JVM test, 0 failures.
- `final-python.log`: migration-guard Python tests, 7 passed, exit 0.

Test totals above overlap and must not be added together. No whole-repository
`:test` pass is claimed. Final filters and suite XML hashes are retained in
`verification-plan.json` and `final-affected-tests-summary.json`. Raw private chat/auth material is not included.

## Review, provenance and limits

Independent read-only review found the concrete defects above; after fixes it found
no additional issue in the T0 and cancellation deltas. Requested GLM returned
SESSION_UNAVAILABLE (configured model unsupported), so the existing built-in
reviewer was used; reviewer agreement is not verification evidence.
AWX was used only for actual failed build/test logs; its classes did not replace
compiler errors, original assertions, or current source inspection.

Local HEAD `2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6`,
branch `codex/owned-runtime-browser-restart`.
Read-only remote HEAD observation:
`b2eaba4679f70ded860b052faa59b29073d0c859`.
The relationship was not established; remote commit/diff/CI history was not used
as current-source evidence. No fetch, commit, push, merge, or branch deletion.

`final-preimage-chain-audit.json` found no unexplained source preimage gaps.
The shared status document had another writer's update; only this task's row was
changed through status_doc's hash-bound compare-and-set. Final source hashes (`final-source-hashes-v2.json`) and the preserved cumulative
patch (`applied-final.patch`) are local task evidence.

Browser/admin proof is **NOT_OBSERVED**, with PROTO_OPEN and the user's explicit
exclusion retained. TRACE, Autograde B, F02, paid/live provider generation,
task_ask product activation, callbacks and InMemory activation were not performed.


## Historical evidence boundary

All sections below are retained historical observations from before implementation.
Their unchecked boxes, pending flags and absent-schema/private-TM verdicts are
superseded by the final evidence above; they do not describe the current state.

## Historical GATE-0 — schema presence (Devin-probed, read-only)

Command: `python -B scripts/f01b_schema_gate_probe.py [--via auto|file|live]`
(exit 0 = GATE0_PASS · 4 = evidence_needed · 5 = probe incomplete → not a schema verdict)

Result — 2026-09-29T02:03Z, lane `live-http` (file H2 locked by Start-RAG JVM — lock is
an answer, not a failure), `observation=complete`, exit **4**:

| item | status |
|---|---|
| table `awx_jobs` | ABSENT |
| table `awx_job_results` | ABSENT |
| table `awx_understanding_receipts` | ABSENT — Codex NEW migration, expected pre-impl |
| column `awx_jobs.admission_key` | ABSENT |
| column `awx_jobs.request_fingerprint` | ABSENT |
| index `awx_jobs_admission_key` (`UNIQUE INDEX`) | ABSENT |

DDL file evidence (file existence ≠ applied schema):
`V20260912__durable_jobs.sql` sha256 `b575731f24154875…` · `V20260912_03__job_idempotency.sql`
sha256 `784b9c766145cc54…` — both present, neither observed applied.

**Verdict: `GATE0_FAIL_EVIDENCE_NEEDED` → stay on F01-A. Devin does not apply DDL;
Codex must not enable B admission before this gate is re-probed PASS.**

### Fresh GATE recheck — 2026-09-29T03:10–03:11Z

- `python -B scripts/f01b_schema_gate_probe.py --via auto`: live HTTP fallback
  after the running JVM held the H2 file; observation `complete`, verdict
  `GATE0_FAIL_EVIDENCE_NEEDED` (probe exitCode `4`). Required `awx_jobs`,
  `awx_job_results`, `admission_key`, `request_fingerprint`, and the unique
  admission index remain `ABSENT`. The two migration files exist but their
  application was not observed.
- `python -B scripts/f01b_tm_probe.py --root . --json`: exit `0`, static hint
  `PRIVATE_TM_SUSPECT` at `JdbcJobService.java:43`. It creates its own
  `DataSourceTransactionManager`; the shared JPA/JDBC transaction and required
  failure-injection rollback were **not** demonstrated. GATE-1 is not passed.
- F01-B product admission remains **HOLD**. F01-A stays in place. No B Java,
  migration, task_ask, callback, or in-memory fallback was activated.

## Historical GATE-1 checklist (superseded by current evidence above)

- [ ] JdbcJobService participates in the same DataSource/verified TM as ChatHistory JPA
      (today it builds its own `DataSourceTransactionManager` — `JdbcJobService.java:43`)
- [ ] Failure-injection proof: fail after JPA USUM insert before JDBC receipt ⇒ both row
      counts 0 after rollback (annotation presence is NOT proof)

## GATE-2 — request eligibility (Codex fills)

- [ ] understanding enabled (global+request) ∧ memorySaveAllowed
- [ ] server-verified owner / session / channel / exact run
- [ ] userMessageId + assistantMessageId determinable at transcript commit
- [ ] durable admission contract (typed handler + schema)
- [ ] approved memory input ↔ transcript relationship validated

## GATE-3 — atomic origin + intent T0 (Codex fills)

- [ ] one physical txn: strict assistant persist (+flush) + job insert for same admission_key
- [ ] afterCommit wake-up only; durable intent already inside T0
- [ ] insert-fail → rollback assistant OR documented durable pending-intent — never silent

## decision.json stub (Codex completes)

```json
{
  "scope": "B-narrow",
  "task_ask": false,
  "callbacks": false,
  "gate0": "GATE0_FAIL_EVIDENCE_NEEDED",
  "tm_proof": "codex_pending",
  "product_enablement": "blocked",
  "devin_product_java_diff": 0,
  "status": "evidence_needed"
}
```

## NEVER (re-cited from contract)

InMemoryJobQueue force-wire · task_ask async activation · n8n/external callbacks ·
Autograde B reopen · F02 scanner refight · commit/push · secrets output ·
silent A-path fallback after B enqueue · busy.lock-as-done · DEFERRED=PASS
