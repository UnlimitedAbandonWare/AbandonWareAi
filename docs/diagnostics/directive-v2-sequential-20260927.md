# DIRECTIVE_V2 sequential execution — 2026-09-27

Task: `directive-v2-sequential-0927-4cc36641`
Canonical root: `C:\AbandonWare\demo-1\demo-1\src`.
Authority: the user-selected goal objective and its CONTINUE override. The older attachments supply technical evidence only; their per-unit approval waits were superseded. No commits, remote mutations, dependency changes or deployments were performed.

## Unit disposition

| Unit | Disposition | Current evidence / outcome | Rollback |
|---|---|---|---|
| BASE | DONE | Active root sourceSets main/java + main/resources; tests src/test/java; app java_clean separate. Java 17.0.13, Spring Boot 3.3.4, LangChain4j 1.0.1. Current source governs. | No change |
| API-01 | HOLD | allow-remote-model-selection=false; api3 manifest disabled. Live cloud catalog also marks openai-economy eligible. Shared flag would admit an additional unapproved route. Existing Groq 90-day evidence-age implementation passes its guards; no TTL reapply. | No change |
| API-02 | HOLD | Depends on an API-01 admitted route. Existing settings/global model policy was inspected; route save/reconnect acceptance was NOT_RUN. | No change |
| API-03 | HOLD | No API-01-verified route to promote. fallbackOnly/weight and auxiliary routing remain unchanged. | No change |
| TRACE-01 | SKIP | Current typed redactor and durable diagnostic summaries already preserve allowed counts/flags and reject secret strings/nested unexpected values. 83 focused tests passed. | No change |
| TRACE-02 | DONE | Production direct-row cap already fixed. Repaired only fake-DOM native API/DocumentFragment behavior in the existing Node fixture, preserving assertions. RED 11/17 → GREEN 17/17; actual installed Chromium harness 12/12. | trace02-fixture |
| TRACE-03 | DONE | Added one Query Transformation group containing ten exact already-produced qtx diagnostic keys. Existing sanitization and HTML escaping retained. Pure renderer; no new model/search/memory calls. Structural missing-group RED; 36 focused tests passed. | trace03 |
| QUERY-01 | SKIP | Verified donor archive hash and compared small transformation behaviors. Current constraint preservation/fail-open behavior already covered; no fixture demonstrated a donor benefit. 40 focused tests passed. No old implementation copied. | No change |
| TRACE-04 | SKIP | Exact assistant-bound v2 pointer, conflict rejection, durable summary after ring eviction, exact-ID 404 and no global latest replacement already covered. 29 focused tests passed. | No change |
| TRACE-05 | HOLD | /events accepts only limit; store truncates before browser filtering. Same-request events outside newest N remain unavailable. Server request filtering/paging would extend the public contract. No unbounded reads/file reader added. | No change |
| TRACE-06 | DONE, retained-history limit HOLD | Fixed a reproduced clock-reversal loss using the existing opaque event ID and newest-first ring order. Initial reconnect replay/wire schema/lifecycle preserved; 13 focused tests passed. Complete gap detection or recovery after eviction remains outside the existing contract. | trace06 |
| VECTOR-00 | DONE, read-only | Classified metadata/legacy compatibility, quarantine, shadow and DLQ guards. Existing config-only diagnostic endpoint HTTP200; actual persisted record contents NOT_OBSERVED. 43 focused guard tests passed. | No change |
| VECTOR-01 | SKIP | No contamination-ingest or retrieval root cause reproduced. No DB/vector mutation, wipe, removeAll or reembedding. | No change |

## Applied files

- main/java/com/example/lms/service/trace/TraceHtmlBuilder.java — exact existing qtx projection group.
- src/test/java/com/example/lms/service/trace/TraceHtmlQueryTransformationTest.java — three renderer/redaction/no-input-mutation cases.
- src/test/js/chat-trace-ui.test.cjs — native-equivalent DOM fixture support; no assertion weakening.
- main/java/com/example/lms/api/DebugEventsDiagnosticsController.java — opaque-ID insertion cursor instead of wall-clock threshold.
- src/test/java/com/example/lms/api/DebugEventsDiagnosticsControllerSseLifecycleTest.java — backward-clock/same-time and evicted-window regressions.
- This report and the task rows in docs/PROJECT_STATUS.md.

Each source cycle has an owned lease, exact preimage bytes, sealed postimages and focused verification under the task directory. Owned leases were released by the guarded edit caller. No foreign hunks, leases, journals or staging were changed.

## Fresh verification

Evidence root: `data/agent-handoff/codex-autonomy/directive-v2-sequential-0927-4cc36641/`.

| Evidence directory | Command surface | Result |
|---|---|---|
| verify-api01 | compileJava, processResources; ExactModelGateway, GroqFreeTierGuard, CloudModelRouteClassifier, ChatModelCatalogService | exit0, 30/30 |
| verify-trace01 | TraceHtmlBuilderRedaction, SafeRedactorFallbackContract, SafeRedactor, TraceDiagnosticProjection, TraceSnapshotRedaction | exit0, 83/83 |
| verify-trace02-node | Node fixture baseline | exit1, 11 pass / 6 fail |
| verify-trace02-fixture | node --test src/test/js/chat-trace-ui.test.cjs | exit0, 17/17 |
| verify-trace02-chromium | node scripts/test_chat_trace_ui_porting.cjs; installed Playwright/Chromium | exit0, 12/12; no network/model generation |
| verify-trace03 | compileJava, processResources; renderer/projection/new group suites | exit0, 36/36 |
| verify-query01 | QueryTransformer constraint, correction, interrupt, redaction, subquery-fallback suites | exit0, 40/40 |
| verify-trace04 | pointer, restorer, session-detail, diagnostics, retention suites | exit0, 29/29 |
| verify-trace06 | compileJava, processResources; SSE lifecycle/failure signal | exit0, 13/13 |
| verify-vector00 | diagnostics, ingest redaction, quarantine DLQ, TrainRag ingest | exit0, 43/43 |
| verify-admin-ui-baseline | node --test scripts/debug_events_ui_contract_tests.js | exit1, 10 pass / 4 fail; fixture TypeError at deferred finish |
| verify-runtime-initial | Verify-RAG.bat | exit0, target=partial; DDL warnings128; did not establish post-SSE JVM reload |

The Java counts total 274 passing test executions across these runs (overlapping suites are deliberately not presented as 274 distinct tests). XML counts were read after each run; runner totals=0 means its XML aggregator was not enabled, not zero tests. Full :test was NOT_RUN; no whole-suite health claim.

TRACE-06 failing structural probe: reflection against the prepatch compiled private Cursor advanced ID first at ts=200, then tested distinct ID clock-back at ts=100; result `clockBackEventAccepted=false`. JShell itself exited0, so this is a failing behavior predicate, not an asserted failing process exit. New lifecycle tests prove accepted delivery after the patch, including same timestamps and a missing cursor in the retained window.

Admin UI failures occurred before the SSE product patch and reproduced unchanged afterward: four asynchronous fixtures call deferred `finish` before its resolver exists (`scripts/debug_events_ui_contract_tests.js:183`). Their product expectations were not executed to completion; these four are neither passes nor proof of UI correctness. Per the user scope rule they were recorded without widening the patch. AWX classified the preserved log as `other`.

## Runtime and browser

Managed dev Close/Start completed. Launcher `20260927-113118-2e6198b8` reports `ready`, `springReused=false`; owned dev PID50420 started at 11:33:10 KST after the changed source was compiled. Final `Verify-RAG.bat -Json` exited0 (run61666e72) with compile/runtime/ports/HTTP/config/freshness/DevWatch/shared-Ollama pass and exceptions warn. `sourcesNewer=false`; DevWatch armed. Result remains `target=partial`, `fullVerification=false`, tests=`not-wired`, and 128 DDL warning/error matches are present. This is fresh scoped runtime evidence, not a clean whole-platform pass. Exact report: `var/debug/dev-20260927-113415-verify.json`; final command log: `verify-runtime-final/command.log`. Shared Ollama and wear roles were not stopped.

The new temporary browser session first rendered hello and a recursion question; after the managed restart, hello and a Pythagoras question again rendered nonempty answers on selected local gemma4:26b, strict selection, Search OFF and RAG OFF. No body HOLD or backend_unavailable was observed. Corresponding final diagnostic events had request/trace hashes `hash:8f9e5b77a0ae` and `hash:2b31a5b40f21`, phase=final, triggerReason=native_success, failureClass=none. Direct original chat transport HTTP status was NOT_OBSERVED; it is not inferred from the UI. A final exact snapshot for the latter request was present in the list, then its HTML endpoint returned404 before the subsequent read. No latest substitute was used. Thus live detailed snapshot retention and live visibility of the new qtx group are NOT_OBSERVED; deterministic rendering and missing-ID behavior are proved by the focused tests, separately from these runtime observations. `/admin/debug-events` returned HTTP200 and was visible without login; enabling LIVE reached `LIVE: connected`. The test stream was disabled and both temporary tabs closed.

Prototype access remains PROTO_OPEN. The actual /admin/debug-events page is anonymously reachable; this is not proof of credential login. Wrong-account and post-logout access-block assertions conflict with the explicit preserve-PROTO_OPEN requirement and were NOT_RUN. No saved authentication state was imported/exported; no auth/security code was changed.

## Vector observation boundary

The config-only `GET /api/vector/diagnostics` returned HTTP200, FederatedEmbeddingStore, dimension1536, provider=openai, modelHash=hash:31a7134a5bdc, allowLegacy=false, bypassIfMetadataMissing=false, Upstash configured=false/writeEnabled=false. This reads configuration/buffer metadata without vector index queries. It does not prove active record cleanliness or embedding generation.

VectorMetaKeys is a key catalog, not a record inventory. VectorStoreService legacy aliases/default doc type do not prove stored legacy data. Existing poison/scope/quality gates route rejected content to quarantine or shadow; AutoLearn defaults to metadata-only staging. Actual index/namespace record inventory, provenance validity, ACL per record and persisted contamination counts remain NOT_OBSERVED. No optional remote info/namespaces endpoint or live JDBC was used.

## Independent and external evidence

GLM presence gate passed, but the requested worker failed with unsupported model/account; state SESSION_UNAVAILABLE, no retry. A bounded native explorer independently reviewed both TRACE-03 and TRACE-06; no blocking finding. Agreement is supporting review, while the tests above are executable evidence.

Local HEAD at intake: 611d8b28e52613ea2bce2a3e4d30d2caaf394e30, branch codex/owned-runtime-browser-restart. Sole remote AbandonWareAi main observed b2eaba4679f70ded860b052faa59b29073d0c859; no common ancestor found, so remote history/CI was not used as proof of current working code.

Groq official compatibility docs checked 2026-09-27: https://console.groq.com/docs/openai. They confirm API format/base URL, not this account's plan, allowance or route admission. No real remote provider generation was run; synthetic unit fixtures and actual local browser generation are reported separately.

## Holds, scope and recovery

API-01 next action: approve a separately scoped direct-selection admission contract that permits exactly api3 without changing the existing openai-economy fallback route; then API-02/03 can be verified. TRACE-05 next action: approve request-scoped filter/paging semantics before changing the public query contract. TRACE-06 history-gap guarantees need an explicit durable/sequence contract; this patch retains best-effort ring behavior.

Recovery cycles: `trace02-fixture`, `trace03`, `trace06` under the evidence root. Each retains before/*.bin, manifest.json, change.diff and checkpoint.json. Use `codex_work_checkpoint.py restore --run <exact-cycle>` only after acquiring an owned target lease and validating current postimages; drift is a conflict, never an overwrite. No rollback was needed.

WP6 snapshot repositories/bundle APIs, WP7 general log appenders/file readers, OTel restructuring, new retrieval engines, donor wholesale copy, model/embedding switches, auth hardening, database/vector deletes, new accounts/dependencies, commits/pushes/deploys were not performed.

Completion follows the user's rule: every unit has DONE/SKIP/HOLD disposition and no further feature is started. Keep all input attachments, source, evidence, final reports, diffs and recovery bytes. There are no proven disposable task artifacts selected for deletion.

