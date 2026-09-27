# mxasain trace porting — A baseline

Checked 2026-09-27, canonical root `C:/AbandonWare/demo-1/demo-1/src`.
Task: `mxasain-trace-a-2311272b`. Authority: the user's A-only request and
`agent-prompts/codex-mxasain-trace-porting-20260927/PASTE_TO_CODEX.md`.
Evidence documents/old ZIP references are read-only hypotheses, not permission
to execute WP3+. No archive overlay, auth change, remote mutation or model/search call.

## WP0 build and owner baseline

- Root wrapper `gradlew.bat`, Gradle 8.7; Java 17.0.13; Node 24.13.0.
- `build.gradle.kts:780`: active root `main/java`, `main/resources`; tests
  `src/test/java`, JUnit Platform `:test`; Spring Boot 3.3.4 and LangChain4j 1.0.1.
- Existing `chatUiTest`, `gatewaySecurityTest`, `crossSubsystemContractTest`
  tasks are separate. No blanket `:test` run is planned.
- All four proposed application targets resolved to canonical C-root with
  `status=PASS`, `reason=match`. Immediate hashes/restorable bytes belong to
  each task checkpoint; archive line numbers are not patch coordinates.
- Entry source leases cover unrelated debug-trail tooling and auth rules.
  This task owns no foreign staging, leases or journals.
- `originEvidenceRoot=null`. GLM probe unavailable (unsupported account/model);
  one native read-only explorer supplied E01–04/E08–18 mapping. Parent owns edits.
- Self-ask: preserve typed diagnostics and later groups; inspect current methods;
  re-anchor old lines; retain v2/DOMPurify/redaction/bounds; patch exact keys/types
  and direct data rows only.

## Evidence classification before application changes

`still_present` is a source-backed condition, not an assertion of runtime failure.
`already_fixed` means the claimed missing capability is already implemented.

| Evidence / finding | State | Live owner / observation |
|---|---|---|
| E01 legacy trace flow | still_present | `TraceFilter.doFilter`, QueryTransformer, KeywordSelectionService, DebugCopilotService and TraceHtmlBuilder own current flow; old design doc is reference only. |
| E02 preservation smoke | moved | Old bean-presence test replaced by focused pointer/meta/SSE contracts; end-to-end preservation not yet run. |
| E03 answer UI | already_fixed | `chat-trace-ui.js` enabled/ensurePanel/sanitizedTrace/wireTraceSteps: per-answer panel, DOMPurify, metadata-only, sorting/filtering exist. |
| E04 v2 pointer | already_fixed | `ChatTraceSnapshotPointerPersister`, `ChatTraceMetaMessageRestorer`, `ChatSessionDetailResponseBuilder`: assistant binding and collision handling exist. |
| E05 / F01 typed loss | still_present | `SafeRedactor.diagnosticValue` summarizes prompt bool/count; `isSecretKey` masks token counts; `TraceHtmlBuilder.sanitizeMeta/safeMetaKey` also hashes their labels. |
| E06 isolated JDK probe | not_reproduced | Historical reproduction only at intake; fresh project RED/GREEN recorded below when run. |
| E07 / F02 nested rows | still_present | `chat-trace-ui.js` sanitizedTrace removes descendant rows after 100; outer trace-kv contains nested data tables. Fresh DOM repro pending at WP0. |
| E08 / F03 persistence | still_present | `TraceSnapshotStore.captureCustom/get` is bounded RAM; v2 pointer holds a 2048-byte summary; full detail across restart is not proven. |
| E09 / F04 exact query | still_present | `DebugEventsDiagnosticsController.events` accepts limit; `debug-events.html` fetches newest N and filters client-side. |
| E10 / F05 SSE | still_present | `DebugEventsDiagnosticsController` initializes cursor yet sends initial backlog; bounded worker/tail exist; epoch/seq/gap contract not observed. |
| E11 / F06 logs | still_present | `logback-spring.xml`, TraceLogger, DebugEventStore have distinct event/trace/search outputs; web events are not all server logs. |
| E12 / F07 input proof | still_present | `ChatWorkflow` prompt assembly markers and llm.call estimates do not prove provider wire delivery. |
| E13 / F08 observation cost | still_present | `TraceFilter` debug flag enables dbgSearch/uaw.ablation.bridge; DebugCopilot explicit action exists. Differential runtime effect not tested. |
| E14 / F09 final capture | still_present | `TraceSnapshotStore.consumeBudget` can suppress capture; controller completion paths exist; async/filter timing remains untested. |
| E15 web-off sync | not_reproduced | `ChatApiController` creates empty SearchResult when web is off; the cited null-result hypothesis is contradicted. |
| E16 OrchTrace/OTel | already_fixed | `OrchTrace`, `MlaOtelBridge.emit` and bridge test exist; event schema remains orch.events.v1. |
| E17 security boundary | still_present | Diagnostics namespace and static ADMIN matchers exist; effective proto-open exposure is outside acceptance. No login/logout/hardening task. |
| E18 / F10 mapping | still_present | qtx.stagePolicy/keywordSelection/dbg.copilot producers exceed explicit TraceHtmlBuilder groups; dedicated full mapping not established. |

F03–F10 are classification only. B/C/D require a new user confirmation after WP2.

## Verification checkpoints

WP0: source/build ownership inspected; runtime, isolated repro and project tests
not yet run. WP1/WP2 results will be appended with fresh commands and counts.
No provider/search generation is permitted for observing traces. Recovery uses
this task's current-byte checkpoints only; never old Git/ZIP source.

## A final checkpoint — 2026-09-27

`CODEX_TRACE_PORTING_A: DONE` applies to A/WP0–WP2 only. E05/F01 and
E07/F02 are fixed and reproduced with project tests. The WP0 table above records
the before-state; F03–F10 remain classification-only and require user confirmation
before WP3+. Evidence packages and the SSOT were not modified.

### Changes and boundaries

- `SafeRedactor.java`: preserve only exact registered Boolean/nonnegative integral
  diagnostic keys, including prompt flags/counts and the specified token counts.
  Wrong types, suffix lookalikes, credentials and raw prompt remain blocked.
  Private immutable summary values survive repeat sanitization; untrusted maps
  that mimic summary fields are sanitized again.
- `TraceHtmlBuilder.java`: keep those exact diagnostic labels and project the
  memory token estimate. `TraceSnapshotStore.java`: reuse the same standard-event
  label registry; capture/persistence/routing/budget behavior is unchanged.
- `chat-trace-ui.js`: cap only direct data rows (headers excluded), exempt layout
  tables, retain later diagnostic groups, and show total/displayed/omitted.
  Keep the 60,000-character input ceiling before DOMPurify. Accepted input is
  reduced as complete DOM nodes, with UTF-16-safe text shortening if needed.
  Oversize input is explicitly reported; its raw HTML is not partially parsed.
  No claim is made that an over-limit HTML payload displays every terminal group.
  The existing upstream snapshot HTML cap is unchanged.
- Checkpoint prerequisite: `scripts/codex_work_checkpoint.py` incorrectly rejected
  name-only Java redaction predicates. A narrow lexical exception with negative
  regressions accepts labels without allowing credential-bearing values/comments.
  No source semantics were renamed to evade scanning.

Changed application files: the four above. Added regression files:
`src/test/java/com/example/lms/trace/TraceDiagnosticProjectionTest.java`,
`scripts/test_chat_trace_ui_porting.cjs`,
`scripts/test_checkpoint_java_redaction_labels.py`.
The proposed separate `TraceHtmlDiagnosticContractTest` was unnecessary: the real
store-to-HTML contract is covered in `TraceDiagnosticProjectionTest`.
Also changed this baseline and the task's rows in `docs/PROJECT_STATUS.md`.

### Fresh verification

All task-relative evidence below is under
`data/agent-handoff/codex-autonomy/mxasain-trace-a-2311272b/`.

| Boundary | Fresh result | Evidence |
|---|---|---|
| WP1 RED | 6 tests: 1 pass, 5 fail (4 semantic failures, 1 test-factory setup error) | `wp1-red-run/` |
| WP1 final GREEN | 68 tests, 0 failures/errors/skips across 3 suites | `wp1-green-run-v3/run.json` |
| WP2 RED | 12 DOM fixtures: 3 pass, 9 fail | `wp2-red-run-v2/command.log` |
| WP2 final GREEN | 12/12 actual Chromium DOM fixtures; 0 network calls, 0 generation calls | `wp2-input-bound-run/command.log` |
| JS syntax | `node --check main/resources/static/js/chat-trace-ui.js`, exit 0 | `wp2-input-bound-run/run.json` |
| Checkpoint scanner | 33 Python tests pass | `scanner-33.log` |
| Independent bounded review | Pre-sanitize unbounded-input finding repaired; reviewer confirmed source/test boundary | task conversation; final `wp2-input-bound` checkpoint |

The WP1 test-factory initialization error was corrected using the existing capture
policy contract; no failed semantic assertion was weakened. Failed intermediate
checkpoints rolled back their sealed changes. The unbounded-input UI candidate
was superseded by the final pre-sanitize ceiling after review.

Commands actually run:

```text
gradlew.bat :test --tests com.example.lms.trace.TraceDiagnosticProjectionTest --tests com.example.lms.trace.TraceSnapshotRedactionTest --tests com.example.lms.service.trace.TraceHtmlBuilderRedactionTest --no-daemon --console=plain --max-workers=2 --project-cache-dir .gradle-mxasain-a
node scripts/test_chat_trace_ui_porting.cjs
node --check main/resources/static/js/chat-trace-ui.js
python -B -m unittest scripts.test_checkpoint_java_redaction_labels scripts.test_codex_work_checkpoint_source_expressions scripts.test_checkpoint_java_call_args
Close-RAG.bat
Start-RAG.bat
Verify-RAG.bat
```

Focused Java outputs used `AWX_SPLIT_BUILD_OUTPUTS=1` and
`AWX_BUILD_HOST_ID=mxasain-a`. The browser used the bundled Playwright package
and already-installed Chromium 1229 via CHROME_PATH; no browser download occurred.
The DOM fixtures cover nested 120/0/1/200 rows, independent tables, later groups,
59,999/60,000-character accepted input, 60,001/70,000-character rejection before
sanitization, Korean/emoji, script/attribute stripping, sorting/filtering, dedup
and metadata-only restoration. The Java fixture uses the real in-memory store and
HTML builder without registering a model/search provider.

### Dev runtime proof and limits

Fresh launcher `20260927-093346-29b21dc1` reached `ready`,
`springReused=false`, role `dev`, PID 62760. All three changed Java owners'
compiled classes precede the new JVM start (00:37:05 UTC). HTTP GET of
`/js/chat-trace-ui.js` returned 200 and exactly matched source SHA-256
`ef4cc454aa1ba3864fd23e68a200c79951b9b63e56280833057f066ddd13a45b`.
Evidence: `runtime-provenance.json`, `served-trace-js.json`, `start-rag/`.

Final `Verify-RAG.bat` exited 0: compileJava/processResources, owned runtime,
ports, HTTP, current freshness and armed DevWatch were observed. Its aggregate
target remains **partial** because the exception check found 128 Hibernate/H2 DDL
error/exception lines (existing constraint/index names). They were also observed
before this restart, but this does not prove the whole repository healthy or
classify unrelated test failures. No DB repair was attempted.
Evidence: `verify-rag-final/command.log`; original tool schema separates
`status=verified` from `target=partial`.

DevWatch's fresh 09:37:59 log says `armed`; a fresh literal `socket ready`
line was not observed. The successful ForceRestart, class-before-JVM timestamps,
current HTTP bytes and final freshness check provide this task's restart proof.
The launcher reports `generationProof=not_requested`. Wear and shared Ollama
were not restarted by this task.

### Hashes, recovery, NOT_RUN and stop

The hash pairs below anchor live methods; complete per-file hashes and the
task-only source diff are in `final-source-hashes.json` and `final-source.patch`.

| Owner | Before SHA-256 prefix | Final SHA-256 prefix |
|---|---|---|
| SafeRedactor | ba75dc0945fe1e3f | ae1bcb6f035729d4 |
| TraceHtmlBuilder | e42a3dbaa5f3f2fd | c771ab6ded62d840 |
| TraceSnapshotStore | 54967c81fff03de0 | b32bfa4a8a07a721 |
| chat-trace-ui.js | cbc996d65e4f616e | ef4cc454aa1ba386 |

NOT_RUN: full `:test` / full `chatUiTest`, generated-answer server E2E,
real SSE network/cursor/gap proof, durable full-detail restoration across restart,
provider-wire semantic proof, hardware/Fold proof. Auth login/logout-block and
`proto-open=false` are excluded from acceptance. F03–F10 and WP3+ are not implemented.

Rollback uses current-byte, hash-bound checkpoints with a fresh target lease.
For complete WP2 rollback, restore `wp2-input-bound` before `wp2-green`
(reverse dependency order); WP1 application rollback is `wp1-green-v4`.
Do not disable DOMPurify or secret masks. Drift refuses restore.
Retain scanner hardening and all reports/logs/recovery files unless separately
authorized. The final checkpoint request has an empty disposable list.

Local commit is deferred by this task: shared source files already contain
pre-task hunks, so a whole-path selective commit would include unowned work.
No staging/commit/push was performed; the foreign staged ownership test remains
staged. The final patch is derived from captured current-byte preimages, not HEAD.

WP2 checkpoint reached. Stop here; B/C/D need the user's next confirmation.
