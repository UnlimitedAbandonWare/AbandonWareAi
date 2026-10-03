# MAX-PUSH implementation report — partial

## Current continuation — 2026-09-28 17:31 UTC

Same contract/task, overall PARTIAL. This section replaces the former current-summary block; historical entries below remain evidence only. F02 is adopted; A1/A2/F01-A/Autograde B and the peer/scanner gate stay closed.

F03 admission/cancellation source contracts remain GREEN (last focused command: 333/333 in f03-provider-green-final, run d2df9905-ad27-463a-93c6-7c08e1ec7d3a). This cycle added reproducible synthetic before/current measurements, not application changes. Exact checkpoint executor bytes and current bytes were separately compiled and run in three fresh JVMs per variant. Four configurations x 30 measured rounds x 3 forks x 2 variants = 720 measured rounds, plus warmups. All variants preserve the expected completion count, valid successes, zero rejection, zero expired callable execution, and zero submitter-side provider work.

| core/max/queue | Median of per-fork post-release queue p95 | Valid tasks/sec after release | Valid successes before/after (3 forks) | Rejections before/after |
|---|---|---|---|---|
| 4/64/256 | 458.0 -> 100.8 us | 278.6 -> 282.1 | 360/360 | 0/0 |
| 4/4/8 | 43.0 -> 68.9 us | 271.0 -> 272.8 | 360/360 | 0/0 |
| 8/8/8 | 52.4 -> 71.9 us | 544.1 -> 547.7 | 720/720 | 0/0 |
| 8/8/16 | 51.9 -> 75.1 us | 544.6 -> 546.7 | 720/720 | 0/0 |

The default 4/64/256 cancellation-prefix scenario retains 252 cancelled queue entries per round before and zero after (22,680 -> 0 over measured rounds); valid batch p95 is 15.218 -> 15.086 ms. Queue wait starts at common worker release and excludes the deliberately held interval. These are component results, not whole-request p95 or real-provider throughput. Nominal fake service is 5 ms but Windows scheduling produces about 15 ms batch timing. The small-queue experiments have higher queue p95 after the change and do not justify a settings change. All operational pool sizes remain unchanged.

F10 measured exact checkpoint/current router cache paths with synthetic clients, three fresh JVMs each and alternating order. Each fork measures 2,000 warm hits, 2,000 unique misses, then 2,048 distinct clients carrying 16 KiB synthetic payloads.

| F10 metric (median across forks) | Before | After |
|---|---:|---:|
| Warm hit p50 / p95 | 6.7 / 10.4 us | 8.6 / 13.4 us |
| Unique miss p50 / p95 | 21.6 / 50.1 us | 34.6 / 76.2 us |
| Cache entries / clients still reachable after GC | 2,048 | 256 |
| Reachable synthetic payload | 32 MiB | 4 MiB |
| Observed used-heap delta | 33,949,448 bytes | 4,338,008 bytes |

F10 therefore proves bounded retention with extra cache-operation cost, not a blanket latency improvement. Used heap is the median of five explicit-GC samples and includes bookkeeping/noise; synthetic payload reduction is not a real SDK/socket/native leak claim. Existing atomic-miss/exact-request tests remain the separate behavioral proof. The prior live-revision deferral is superseded by supported-lifecycle evidence: active endpoint/credential configuration is assembled at startup; no same-context mutation endpoint/event is present. ModelSettingsController persists requested model selection, already namespaced in the cache key. ConfigRevisionInvalidatesOldRequestedClientTest creates and closes actual Spring contexts with independently changed synthetic endpoint, credential, or configured model. Each new context has an empty distinct cache, builds a new client using its newly bound values, and reuses only that context's client. All3 cases pass; five focused suites total38/38 (run6cb5be5c-fd3e-49c8-ae1b-9352f619b761, ticketcv-29c514f75b4d44af, no input drift). This verifies the supported restart revision boundary; live hot rotation remains unsupported/unclaimed. No new refresh subsystem, factory mutation, property, or credential change was introduced.

Conditional decisions now closed by current source:
- F11 SKIP_NO_APPROVED_PARTIAL_PATH: TimedChatModelCaller returns one complete response; ChatWorkflow applies final verification/release before returning ChatResult; ChatApiController then chunks visibleFinalText. No approved provider-delta release path exists to adapt. No per-delta cancellation/runtime claim.
- F12 SKIP_INACTIVE_SOURCE_NO_ACTIVE_CALLER: only the exact service/service/rag/bm25/Bm25Index target counts. It and Bm25Retriever are plain utilities; UnifiedRagOrchestrator has optional injection, with no active-main constructor/bean registration. No new bean/index/caller was introduced. Runtime bean inventory remains not_observed.
- F06 remains SKIP_INACTIVE_RUNTIME from f06-final-runtime-writer-status.json (enabled=false, disabledReason=disabled, lastWriteStatus=disabled on the last verified dev PID41640).

Evidence: performance-measurement-summary.json plus f10-measurements/*.json and f03-measurements/*.json under the exact task root. f10-measure-run-02 run2865318a-ddc4-484d-8de8-8c090a27fd45 and f03-measure-run run46da7347-9b60-4bbc-8e9e-d31da2d4e04f both exit0; cooperative tickets cv-6c702df437c74a0f and cv-2a3e7a32b0674fe6 are VERIFIED_PASS with no input drift. The first F10 command failed because its task was registered after task selection; registration was repaired, then both exact variants ran. The failed receipt remains evidence, never PASS.

All39 adopted product/test postimages match, including the new lifecycle test (f10-lifecycle-source-audit.json). Only that test was added; no application source changed or server was restarted this cycle. Last runtime proof remains f03-runtime-verify-final: dev run20260929-014822-8e481c79, PID41640, compile/process/HTTP/freshness/DevWatch verified; H2 DDL132 warnings keep target partial. Runtime state was not freshly reasserted by these benchmarks.

Required unresolved work is F01-B only. Fresh read-only target-schema probe f01b-store-observation-current (run daf3545f-5670-4020-96d9-44783be0a183, exit0) again sees chat_session/chat_message but no awx_jobs/awx_job_results. F01-B is not PASS. Broader provider/end-to-end latency stays not_observed within the explicit no-real-E2E scope.

F01-B activation decision, ready for review:
1. Reusing the existing JDBC JobService requires both explicit migrations main/resources/db/migration/V20260912__durable_jobs.sql and V20260912_03__job_idempotency.sql, including admission_key/request_fingerprint and their unique index. No migration was applied.
2. This is wider than an UNDERSTANDING-only table change: JobConfig defaults to JDBC; JdbcJobService.start already schedules polling/maintenance and has four worker slots. TasksApiController.registerPersistedWork registers task_ask, which invokes continueChat and can deliver/retry callbacks. Provisioning makes that existing asynchronous API operational when requests are submitted; UNDERSTANDING is not yet a registered handler.
3. For F01-B, add a deterministic per-source/revision commit receipt under ChatHistory's existing session transaction, atomically with the USUM message. Generic job admission/result success is insufficient because handler side effects precede the job-result transaction. Recheck owner, actual original message IDs, revision, ordinary-memory consent epoch, deletion and run identity before calculation and again at commit; no re-generation or late SSE.
4. Keep the existing direct path until the job schema, transactional receipt, and duplicate-delivery/deletion/revocation/pending-context tests pass. Then move only derived understanding after approved original transcript commit. Do not alter the completed F01-A implementation or introduce a second queue/lossy runAsync fallback.
5. Needed decision: whether the shared JDBC store may be provisioned/reused with its existing task_ask activation consequences. The v2 directive explicitly cautions against forcing a dormant JobQueue into this seam and allows separate storage design when no suitable store exists. Until that scope is resolved, this lane remains deferred; other required/conditional lanes retain their evidence.

conflicts_ATT_CTX_NW=none for these declared targets; repositoryWideHold=false. No peer/scanner edit, DB mutation, provider generation, full suite, admin Browser, glasses E2E, staging, commit or push.

Contract: DEMO1-CODEX-MAX-PUSH-B-PERF-20260928.
Task: max-push-b-perf-0928-50df21d2. Canonical root: C:/AbandonWare/demo-1/demo-1/src.

**PARTIAL, not Done.** A1/A2/F01-A remain closed GREEN. F02 is GREEN_SOURCE (177 tests); its old peer-unblock hold is resolved. F01-B is DEFERRED_STORAGE_CONTRACT on fresh live schema evidence. F03 scoped ownership, F04, F08, F09 and F07 have focused GREEN evidence below; remaining conditions stay open.

## Changes and focused verification

| Step | Applied change | Fresh evidence |
|---|---|---|
| A1 M9 | ChatRequestDto tracks raw omission while getSearchMode defaults to AUTO; ChatSessionMetaMerger restores only omitted modes. Existing M5 behavior retained. | RED2 -> Java102 / UI5 GREEN |
| A2 ReleaseGate | Four-argument attachment owner/query mock and verification in ChatWorkflowFinalVerificationReleaseGateTest. No production gate change. | Baseline 43 tests / 5 failures -> 58/58 GREEN, including all 43 ReleaseGate tests |
| A3 B04–B09 | No product changes; bounded synthetic/current regression cases did not reproduce a defect. | 37/37 routing/FocusMemory/context/DisplayRelay plus A2 release/persistence coverage |
| F01-A | UnderstandAndMemorizeInterceptor.prepare computes outside terminal commit; commitPrepared retains memory/meta/SSE work. ChatWorkflow rechecks cancellation before existing FinalizedMemoryPersistence. AnswerUnderstandingService bounds generation by configured, caller and request budgets. | RED2 -> focused97 / UI5 GREEN |

F01-A's meaningful RED evidence is `f01a-red-fixture`, not the earlier compiler/fixture failures. It demonstrated cancellation blocked by summary preparation and generation after request-budget exhaustion. GREEN additionally verifies side-effect-free preparation, no duplicate generation at commit, exact-run SSE, disabled/empty behavior, all three wait caps and subscription disposal. This proves lock/cancellation boundaries in synthetic tests; it does not measure live provider latency or remove summary work from the answer's critical path. F01-B remains required.

The 97-test command selects ChatWorkflowFinalVerificationReleaseGateTest, AnswerUnderstandingServiceUnicodeBoundaryTest, UnderstandingPreparationContractTest, ChatWorkflowInteractionEvidencePolicyContractTest, FinalizedMemoryPersistenceTest, ChatApiAgentPromptEvidenceTest, ChatSessionMetaMergerTest and RagMemSteerDynamicToggleProbeTest. Each run's command/exit metadata is in run.json and output in command.log. Gradle evidence includes JUnit counts; sourceIdentity is populated only where explicitly selected. Node's 5/5 count is in command.log. Checkpoints preserve source preimage/postimage hashes. Counts overlap across runs.

## Current F02/F01-B boundary

F02: GREEN_SOURCE. Completion-order harvesting is separated from stable ordinal reduction; one monotonic level allowance begins before admission and bounds per-lane waits and rewrite/retry admission. RED: f02-red-resume (2 expected failures). GREEN: f02-contract-green-final (177/177, exit 0), including reversed completion order with unchanged dedup owner/query/rank and zero new provider admission after rewrite exhausts the original budget. Checkpoints f02-04-source and f02-07-contracts. Peer scanner unchanged; G1-G4 passed. Historical F02 HOLD is resolved. Runtime through F09 was refreshed; see the continuation evidence below.

F01-B live schema inspection returned only chat_session/chat_message, with no durable awx_jobs/awx_job_results. Per v2 F01-B absent-store branch, no lossy background task or automatic DDL was introduced. The source/table impact and required consent/revision/idempotent commit proof are recorded in max-push-progress-0928.md. Independent seams continue; repositoryWideHold=false.

## Runtime and preserved limits

Runtime: the initial stale-candidate verification was resolved by the matching dev Close/Start. After a later concurrent NovaFocusService edit triggered DevWatch, the replacement run `20260928-215401-03884717` reached ready with PID5436 and springReused=false. `f01a-runtime-verify-current` returned exit0 at 13:01 UTC: compile passed, ownership identity matched, HTTP checks passed, freshness=current and DevWatch observed. The tool's target remains partial because 132 H2 DDL exception lines remain; no database repair or generation request was made. The PID's actual classpath is `build/desktop-meta-display/classes/java/main`; javap confirms prepare, commitPrepared and the bounded understand overload. All five inspected class files predate this JVM. Test-build class files are currently absent at the recorded codex-maxpush path, so runtime/test bytecode equality is not_observed. Earlier wrong-directory and failed-comparison artifacts were corrected with their original bytes retained in the correction checkpoint.

No commit/push/staging/remote mutation, full suite, real provider E2E, admin Browser, glasses test, database repair or cleanup sweep was performed. B00/B01/B02/D1/B03/T-STRUCT/M5 changes are preserved; B02 and M5 smoke are included. H2 DDL132 remains a classified platform limitation.

conflicts_ATT_CTX_NW=none observed on these targets. Scanner preflight hold resolved by peer YES; goal remains open under DEMO1-CODEX-MAX-PUSH-F02-GO-AFTER-DEVIN-YES-20260928. The runtime paragraph above is historical F01-A evidence; current runtime evidence follows.

Evidence/handoff root: `data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/`. Ongoing acceptance matrix: `acceptance.json`. Progress document: `docs/diagnostics/max-push-progress-0928.md`.

## Continuation evidence — 2026-09-28 15:54 UTC

G1-G4 remain resolved by the once-only peer check. No scanner or peer evidence was modified. A1/A2/F01-A and Autograde B remain closed.

| Item | Current result | Evidence under the task root |
|---|---|---|
| F03 | Scoped request-owned admission/cancellation GREEN; global executor policy/tuning deferred until other caller rejection contracts are established. | f03-contract-green: 210/210, exit 0 |
| F04-A/B | Canonical-key dedup, atomic batch OWNER/JOIN reservations, owned work before joins, bounded waits and array isolation GREEN. | f04b-contract-green: 39/39, exit 0; duplicate-batch and array-alias REDs preserved |
| F08 | Metadata-only session lookup used by three request setup paths; synthetic sessions of 10/1000/5000 messages select/load zero message rows for settings. | f08-green-focused: 21/21; f08-contract-green-scoped: 132 passed, 1 skipped, 0 failed |
| F09 | Ordinary sync/chat/stream routes make zero eager HTML renderer calls; durable sanitized assistant-bound pointers retain authorized late HTML reconstruction. | f09-red: 3 expected failures; f09-green-scoped: 115/115 |
| F07 | Incremental determinant factor with full legacy rerun on numerical boundaries; ordered identity and existing trace equal on 450 fixed-seed synthetic cases (182 conservative fallbacks). | f07-red: 1 work-count failure (114920 shingle accesses); f07-green: 32/32; f07-callers: 159/159 |

F07 isolated Java 17 stage medians (4 warmups, 9 alternating samples): n/k 8/4: 0.273→0.205ms, 34952→18424 allocated bytes; 60/10: 2.736→0.817ms, 1830864→159848 bytes; 100/16: 9.100→0.697ms, 8881376→269224 bytes; 160/20: 16.211→0.984ms, 20325128→437152 bytes. These are synthetic rerank-stage observations, not whole-request p95 or retained heap. The old selector remains private and trace-silent; uncertain cases preserve legacy order.

Baseline limitations are explicit: F08 title-length failure was reproduced against source preimage. Four additional caller failures were reproduced against the exact four-file F08 source preimages in f08-caller-preimage-02 (6 selected, 4 failed); the final F08 scope excludes exactly those three method names via f08-focused.init.gradle. F09's old attachment-suppression source-text assertion also fails on the preimage (f09-fixture-red), and f09-focused.init.gradle excludes that one method. No excluded failure is counted as PASS; no adjacent attachment/UI repair was made.

Runtime through F09: dev Close/Start created run 20260929-004636-fbe23f0e, PID804, ready, springReused=false. perf-f09-runtime-verify-current (runId3f394c10-ef42-4baa-bde6-9d18522acc35) returned exit0 with compile/HTTP/owner identity passed, sourcesNewer=false and DevWatch armed. H2 DDL132 remains a warning and tool target=partial. F07 triggered a later DevWatch rebuild; final adopted-source runtime proof is recorded below. No provider generation, real E2E, database repair or full suite.

F05-A/B and bounded F10 are now adopted with focused proof below; F06/F11/F12 and the additional F01-B/F03/F10 contracts retain their specific deferrals. This remains PARTIAL, not Done.

## Latest bounded result — 2026-09-28 16:13 UTC

Status remains PARTIAL; no previously closed A1/A2/F01-A/Autograde B work was reopened. The old peer HOLD is resolved.

| Item | Applied result | Fresh focused proof |
|---|---|---|
| F05-A | One protected row no longer forces every admitted row into a singleton embedding call. Input ID/metadata/segment/vector alignment remains positional; every durable write rechecks its source. | f05a-red-grouped: expected RED1; f05a-green: 26/26 |
| F05-B | Contiguous entries with identical sessionId and full extraMeta share one existing current-source commit callback. Different source fences stay separate. Same-source synthetic 32-entry batch uses one calculation and one store call. | f05b-red: expected batch32, actual1; f05b-green: 35/35 |
| F10 | Existing Caffeine dependency supplies atomic requested-client loading, maximumSize256, expireAfterAccess30min and stats. Exact manual selection remains separate. No shared client is closed on eviction. | f10-red: 1024 retained entries and concurrent duplicate construction; f10-green: 10/10 including exact selection and build-failure recovery |

F05 failures preserve existing semantics: malformed vectors write nothing; revocation, correction or deletion during embedding prevents durable writes; distinct-source partial store failure marks only committed IDs. An ambiguous same-source multi-ID write failure marks none successful and retries the same deterministic IDs. Backend transaction atomicity is not claimed; the synthetic upsert fixture verifies no fresh retry IDs or false durable accounting. The first f05a-red used separate trace groups and is fixture evidence only; f05a-red-grouped is the actual mixed-batch defect RED.

Final runtime: matching dev Close/Start produced run 20260929-010905-66a38555, ready PID42728, springReused=false. perf-final-runtime-verify (runId27e11638-d5b4-4666-a5b1-1c2750c14d03) returned exit0: compile, owner identity, ports, HTTP, sourcesNewer=false and DevWatch armed. H2 DDL132 remains a warning; target=partial. perf-final-runtime-identity.json verifies the actual JVM classpath and all13 changed product class files compiled before JVM creation. Test-output class files were absent at the recorded codex-maxpush class directory, so test/runtime byte equality is not_observed. perf-final-source-audit.json matches all29 owned product/test files to their latest verified checkpoint postimages (mismatchCount0). The last scoped git diff --check exited0 (line-ending warnings only); no staging or commit.

Remaining conditions are not PASS:
- F01-B: DEFERRED_STORAGE_CONTRACT. Live durable job tables absent. Next: settle/provision the bounded job+exact-revision commit receipt contract recorded above, then verify duplicate delivery, consent/revision/deletion fences and pending-summary continuity before moving summary work.
- F03: request-owned SelfAsk admission/cancellation is GREEN210; global rejection-policy replacement and pool tuning are deferred. AnalyzeWebSearchRetriever still has serial-on-rejection behavior; Zero100WebTimeboxAspect and HybridWebSearchEmptyFallbackAspect have unguarded submissions. Next: establish their fail-soft rejection contracts before any global policy switch; no thread-count tuning was applied.
- F06: PENDING_RUNTIME. Current effective Neo4j writer activation has not been observed; static default is disabled. Next: obtain the existing redacted Neo4jKgChunkWriter.status projection from the target run. Do not activate it or issue a database write merely to satisfy this task.
- F10: bounded atomic construction is GREEN10, but live config-revision invalidation is deferred. KeyResolver is an Environment lookup facade; relevant property setters are bootstrap/test-owned, with no production revision/event producer found. Canonical Spring restart recreates the cache; no new revision property/fingerprint was invented. Warm/unique-miss latency and retained heap are not_observed.
- F11: DEFERRED_STREAMING_CONTRACT. Installed LangChain4j1.0.1 streaming callbacks return void and expose no cancellation handle; current selected-provider factories and ChatService are buffered. Next: supply a provider cancellation owner and bounded per-run output/release contract before the six streaming regressions. No raw deltas bypass current global verification.
- F12: DEFERRED_NO_ACTIVE_BEAN_OBSERVED. Canonical Bm25Index has no observed bean/caller activation. Next: runtime caller proof if this lane is enabled later; source changes0.

holdScope=the conditions above; firstBlockingRule=required durability/authority/activation proof; blockingEvidence=the cited source and verification artifacts; independentWorkCompleted=F02/F03-scoped/F04/F05/F07/F08/F09/F10-bounded; repositoryWideHold=false. No full suite, admin Browser, real provider/glasses E2E, schema mutation, peer/scanner edits, forced lease release, commit or push. Whole-task completion/cleanup is not claimed.
