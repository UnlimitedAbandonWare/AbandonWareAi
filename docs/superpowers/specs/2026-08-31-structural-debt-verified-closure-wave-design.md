# Structural Debt Verified-Closure Wave Design

**Status:** written specification approved in chat on 2026-08-31

**Parent objective:** continue the approximately-1,000 structural/design repair goal without counting detector noise, duplicate manifestations, lines changed, or unverified patches as repairs.

**Predecessor:** `docs/superpowers/specs/2026-08-31-current-tree-structural-debt-ledger-design.md`

## Objective

Reconcile the first ledger's eleven `BROAD_CATCH_NO_BREADCRUMB` groups against the repository's brace-aware catch contract, preserve every original issue and root-cause identity, reject detector false positives without repair credit, and close each genuine group through an independently proven repair cycle.

The wave finishes with a canonical ledger that can durably represent both `REJECTED_FALSE_POSITIVE` and `VERIFIED_CLOSED`. It must not depend on a hand-edited generated ledger or a prose-only completion report.

## Approved Outcome

The frozen intake contains eleven `OPEN / ELIGIBLE` root-cause groups. Current-source reconciliation produced these dispositions:

| Disposition | Groups | Repair credit |
|---|---:|---:|
| `REJECTED_FALSE_POSITIVE` after brace-aware contract proof | 5 | 0 |
| Genuine bounded repair candidates | 6 | at most 6, one per independently verified group |

The six candidate groups are not pre-counted as closed. Each earns one credit only after its own preimage, RED, minimal patch, focused GREEN, evidence-fingerprint transformation, applicable gates, secret scan, duplicate-owner check, and final Desktop proof pass.

The parent target remains 900 through 1,100 distinct `VERIFIED_CLOSED` root-cause groups. If all six candidates close, this wave contributes six credits and leaves a minimum closure gap of 894. The predecessor metric `targetGap` remains the candidate-supply metric defined by the predecessor specification; this wave reports the closure gap separately and does not silently change that field's meaning.

## Decision Evidence

The current canonical ledger and a fresh run of `scripts/harmony_pressure_report.py` agree on all eleven open rows. The ledger is therefore not stale.

The discrepancy is semantic:

- `scripts/harmony_pressure_report.py` uses a regular-expression catch start and a fixed 32-line look-ahead.
- `scripts/awx_mcp_toolbox.py` strips comments and strings, finds the actual brace-delimited catch body, recognizes explicit terminal and deferred-rethrow behavior, and validates named bounded-catch contracts.
- Five ledger groups contain only already-bounded catch bodies under the brace-aware contract.
- Six ledger groups still contain at least one broad catch with neither an accepted bounded outcome nor a redacted local breadcrumb.

The current quantitative producer also cannot persist closure. Its validation requires the category's initial status tuple, requires `supersedes=[]`, and requires `verifiedClosedRootCauseGroups=0`. Application-source patches alone would therefore disappear from later detection while leaving no durable closure credit.

## Scope

### In scope

- one shared, brace-aware Java catch contract used by both harmony evidence consumers;
- exact reconciliation of the frozen eleven groups;
- a redacted append-only closure journal;
- producer support for validated rejected and closed historical rows;
- six sequential application-source repair cycles;
- focused Python and JUnit contracts;
- current-baseline ledger, metrics, source-health, sourceSet, dependency, compile, diff, duplicate-owner, and secret verification;
- a final Desktop evidence report.

### Out of scope

- changing RAG prompts or the `PromptBuilder` boundary;
- changing provider selection, credentials, endpoints, or external request policy;
- calling Brave, OpenAI, Supabase, or another external provider for proof;
- Browser or Computer execution when no browser-visible owner changes;
- S01-S07 algorithm-body changes;
- mass logging, broad formatting, file splitting, or unrelated cleanup;
- promoting any `REVIEW_REQUIRED` file-size, cross-subsystem, or duplicate-FQCN row;
- changing the approximately-1,000 target by manufacturing new categories or splitting one cause into multiple credits;
- stage, commit, push, deploy, database mutation, or credential mutation.

## Architecture

### 1. Single catch-contract owner

Add `scripts/harmony_catch_contract.py` as the small, pure owner of Java catch-body parsing and classification. It contains only:

- comment/string stripping that preserves line positions;
- balanced-brace catch-body iteration;
- exact broad-catch-type classification for `Exception`, `RuntimeException`, and `Throwable`, including multi-catch members;
- redacted breadcrumb/outcome recognition;
- named bounded-catch contracts;
- deterministic per-file evidence rows containing repository-relative path, catch line, caught-type class, and reason code;
- no source snippets, exception messages, absolute paths, URLs, environment values, or command execution.

`scripts/harmony_pressure_report.py` and `scripts/awx_mcp_toolbox.py` both import this owner. They may project different aggregate views, but they must not maintain independent parsing regexes or independent bounded-contract tables.

The public per-file ledger projection remains count-only. Catch line numbers are allowed in the private cycle evidence package, but the public quantitative ledger continues to store only path, symbol, category, reason, and numeric counts.

### 2. Frozen intake

Before changing the detector or application source, freeze these predecessor values in the repair workspace:

- predecessor baseline ID;
- predecessor ledger payload hash;
- predecessor metrics semantic hash;
- ordered eleven source issue IDs;
- ordered eleven root-cause-group IDs;
- ordered evidence fingerprints;
- each declared target's existence, Git state, byte size, and SHA-256;
- exact source branch and HEAD;
- declared test targets and their preimages;
- staged count, index-lock state, top-level PatchDrop count, and active/corrupt/expired lease counts.

The frozen intake is immutable evidence. A changed target preimage stops only the overlapping cycle. A changed predecessor ledger identity stops the whole wave because dispositions could no longer be linked to the approved intake.

### 3. Closure journal

Add `verification/structural-repair-closure-journal.jsonl` as the canonical redacted input that carries historical dispositions forward. It is append-only by logical identity: an existing event is never edited in place. A correction appends a new event whose `supersedesEventId` identifies the prior event.

Each line has exactly these fields:

```json
{
  "schemaVersion": "awx.structural-repair-closure-event.v1",
  "eventId": "<sha256-of-canonical-event-payload>",
  "eventType": "REJECTED_FALSE_POSITIVE | VERIFIED_CLOSED",
  "rootCauseGroupId": "<64-hex>",
  "sourceIssueId": "<64-hex>",
  "sourceBaselineId": "<64-hex>",
  "sourceEvidenceFingerprint": "<64-hex>",
  "sourcePath": "<redacted-repository-relative-path>",
  "sourceSymbol": "<redacted-symbol>",
  "sourceCategory": "BROAD_CATCH_NO_BREADCRUMB",
  "sourceNumericEvidence": {"broadCatchCount": 1, "lineCount": 1},
  "eventBaselineId": "<64-hex>",
  "targetPreimageSha256": "<64-hex>",
  "targetPostimageSha256": "<64-hex>",
  "scopedDiffSha256": "<64-hex-or-zero-hash-for-no-application-source-diff>",
  "redProofId": "<allowlisted-proof-id>",
  "redProofSha256": "<64-hex>",
  "greenProofId": "<allowlisted-proof-id>",
  "greenProofSha256": "<64-hex>",
  "eventBaselineProofId": "<allowlisted-proof-id>",
  "eventBaselineProofSha256": "<64-hex>",
  "patchStateProofId": "<allowlisted-proof-id-or-null>",
  "patchStateProofSha256": "<64-hex-or-null>",
  "fingerprintDisposition": "DISAPPEARED | ACCEPTED_BOUNDED_TRANSFORMATION",
  "sourceSetGate": "PASS",
  "dependencyGate": "PASS",
  "compileGate": "PASS",
  "duplicateOwnerGate": "PASS",
  "secretNewHitCount": 0,
  "desktopProof": "PASS",
  "supersedesEventId": null
}
```

Canonical event hashing excludes `eventId` itself. The journal contains no timestamps so identical evidence produces identical bytes and event IDs.

`eventBaselineId` is the post-cycle baseline captured when the event was created. It is not required to equal a later bundle's baseline. Its redacted baseline-summary proof remains in the repair workspace, while the event's target postimage must continue to match unless a later declared cycle supersedes that target.

`eventBaselineProofId` identifies a canonical summary JSON under the declared proof root. That summary contains exactly schema version, baseline ID, branch token, HEAD hash, ordered declared-target path tokens, Git states, sizes, and content hashes. `eventBaselineProofSha256` must match its bytes. It contains no source content or absolute path.

For `REJECTED_FALSE_POSITIVE`:

- `targetPreimageSha256` equals `targetPostimageSha256` for the application source;
- `scopedDiffSha256` is the all-zero SHA-256 value;
- RED proves that the predecessor detector classified the frozen catch incorrectly;
- GREEN proves the shared brace-aware contract recognizes the existing bounded behavior;
- `patchStateProofId` and `patchStateProofSha256` are `null` because no application-source patch state exists;
- the event earns zero repair credit.

For `VERIFIED_CLOSED`:

- preimage and postimage hashes differ unless a separately justified behavior-preserving detector transformation is the actual root-cause repair;
- the scoped diff hash is nonzero;
- RED and GREEN identify one focused behavior or structural contract;
- `patchStateProofId` identifies the exact preceding `PATCHED_UNVERIFIED` row and `patchStateProofSha256` matches that row's canonical bytes;
- the original fingerprint disappears or is transformed into an accepted bounded contract;
- all closure gates are `PASS` and the secret new-hit count is zero.

Journal paths and proof IDs are allowlisted. Proof hashes refer to files under `.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/`. The producer verifies the files exist, stay under that workspace, match their declared SHA-256, and contain only allowlisted summary fields. Raw test logs remain private workspace evidence and are not copied into public ledger rows.

### 3.1 Private patch-state progress ledger

The exact private progress path is:

```text
.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/repair-progress.jsonl
```

It is append-only. Each application-source cycle appends one `PATCHED_UNVERIFIED` row before terminal closure validation. The row contains exactly:

```json
{
  "schemaVersion": "awx.structural-repair-progress.v1",
  "patchStateId": "<sha256-of-canonical-row-payload>",
  "state": "PATCHED_UNVERIFIED",
  "rootCauseGroupId": "<64-hex>",
  "sourceIssueId": "<64-hex>",
  "sourceBaselineId": "<64-hex>",
  "eventBaselineId": "<64-hex>",
  "targetPreimageSha256": "<64-hex>",
  "targetPostimageSha256": "<64-hex>",
  "scopedDiffSha256": "<nonzero-64-hex>",
  "redProofId": "<allowlisted-proof-id>",
  "redProofSha256": "<64-hex>",
  "greenProofId": "<allowlisted-proof-id>",
  "greenProofSha256": "<64-hex>"
}
```

Canonical hashing excludes `patchStateId`. The row has no timestamp. The producer locates it by `patchStateProofId == patchStateId`, recomputes its hash, requires `patchStateProofSha256` to match the row's canonical bytes, and requires every shared identity/hash field to equal the terminal closure event. A rejected-false-positive event never references this file.

### 4. Producer overlay

`scripts/dynamic_rag_quant_audit.py` reads the journal as an explicit declared input after building the current detection universe and before sorting, capping, counting, and publishing.

For every journal event it must:

1. locate the exact predecessor issue and group in the frozen intake;
2. recompute the event ID;
3. verify path, symbol, category, original numeric evidence, original fingerprint, and source baseline;
4. reject duplicate active events for one group unless a valid supersession chain selects one terminal event;
5. verify current target and evidence hashes;
6. prove the current detector no longer emits the predecessor fingerprint;
7. validate event-specific gates;
8. emit one historical ledger row linked to the current baseline and artifact bundle;
9. set `supersedes` to a stable hash of `sourceBaselineId | sourceIssueId | sourceEvidenceFingerprint`;
10. count `VERIFIED_CLOSED` groups once and rejected groups zero times.

Historical overlay validation is an explicit alternative to the initial detection-row validation; it is not an additive relaxation. The producer adds `is_valid_historical_overlay_row` with only these accepted terminal tuples:

```text
REJECTED_FALSE_POSITIVE / REJECTED_BY_CONTRACT
VERIFIED_CLOSED / ELIGIBLE
```

The branch requires the original issue ID, group ID, category, path, symbol, evidence reason, evidence fingerprint, numeric evidence, severity, and proof command to match the frozen source row. `supersedes` remains the existing list field and contains exactly one 64-hex value computed from canonical bytes of `sourceBaselineId | sourceIssueId | sourceEvidenceFingerprint`. Initial current-detection rows still require their category's original tuple and `supersedes=[]`. No other status/eligibility combination or supersession shape is accepted.

Historical rows preserve the original issue ID, group ID, category, reason, and numeric evidence. Their `baselineId` becomes the current bundle's baseline ID; the `supersedes` relationship preserves the source-baseline identity. This uses the predecessor ledger's already-declared status and supersession fields and does not introduce a second competing ledger.

The producer continues to publish baseline, ledger, and metrics transactionally with metrics last. Journal or proof failure must preserve all prior output bytes.

The journal is an append-only input/history artifact. It is not a fourth output of `publish_bundle` and is never rewritten during baseline/ledger/metrics publication.

The exact journal path is excluded from the source baseline manifest. Otherwise `eventBaselineId` would recursively depend on the journal bytes that contain that ID. The exclusion is exact-path only:

```text
verification/structural-repair-closure-journal.jsonl
```

No broader `verification/` exclusion is introduced. The ignored proof root is already outside the tracked/non-ignored source manifest. The producer still binds both evidence inputs to the public transaction through a required metrics object:

```json
{
  "closureHistorySummary": {
    "journalPayloadSha256": "<64-hex>",
    "proofSetSha256": "<64-hex>",
    "eventCount": 0,
    "rejectedFalsePositiveRootCauseGroups": 0,
    "verifiedClosedRootCauseGroups": 0
  }
}
```

`proofSetSha256` is computed from the ordered canonical pairs of every referenced proof ID and verified file SHA-256, not from an unrestricted directory walk. `auditRunId` includes both history hashes, and `semanticArtifactHash` includes the entire closure-history summary. The scorecard validates the hashes and requires the summary's verified count to equal `ledgerSummary.verifiedClosedRootCauseGroups`. A journal/proof change therefore changes the audit identity without contaminating or recursively defining the source baseline.

`verifiedClosedRootCauseGroups` is recomputed from emitted plus non-truncated historical rows instead of being forced to zero. `eligibleRootCauseGroups` continues to count only `ELIGIBLE / OPEN` groups. `targetGap` retains its predecessor candidate-supply meaning and becomes `max(0, 900 - distinct(ELIGIBLE / OPEN groups plus VERIFIED_CLOSED groups))`; this is identical to the predecessor formula while no closures exist. The wave report separately computes `closureTargetGap = max(0, 900 - verifiedClosedRootCauseGroups)`.

### 4.1 Required CLI and Gradle inputs

The producer adds two explicit repository-relative arguments:

```text
--closure-journal verification/structural-repair-closure-journal.jsonl
--closure-proof-root .superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave
```

The journal file is required and may be zero bytes before the first valid event. An absent journal is `closure-journal-missing`; malformed or nonempty-without-terminal-newline input is `closure-journal-malformed`. The proof root may be absent only while the journal is empty. A nonempty journal requires an existing non-reparse proof root and all referenced allowlisted files.

`dynamicRagQuantAudit` declares the journal as a required relative-path-sensitive input file and the proof root as an optional relative-path-sensitive input directory whose absence is allowed only under the empty-journal rule. The task passes both arguments explicitly. Neither path is discovered through environment variables or directory scanning.

`scripts/source_health_scorecard.py` and its focused tests are extended only enough to validate `closureHistorySummary`, the history hashes, and count equality. They do not become a second closure-state owner.

### 4.2 Exact public aggregate semantics

The shared brace-aware rows are authoritative. `broadCatchWithoutLocalBreadcrumbApprox` retains its legacy field name for consumer compatibility, but its value becomes the exact count derived solely from shared rows classified as broad and unhandled. No consumer may add regex-look-ahead-only rows to that value. Tests name this migration explicitly and require the harmony report and toolbox projection to agree on the same eleven owner fixtures.

### 5. State transitions

Only these transitions are allowed in this wave:

| From | To | Required evidence | Repair credit |
|---|---|---|---:|
| `OPEN / ELIGIBLE` | `REJECTED_FALSE_POSITIVE / REJECTED_BY_CONTRACT` | frozen pre-evidence plus brace-aware bounded-contract RED/GREEN | 0 |
| `OPEN / ELIGIBLE` | private cycle state `PATCHED_UNVERIFIED / ELIGIBLE` | stable preimage plus scoped application-source diff | 0 |
| private cycle state `PATCHED_UNVERIFIED / ELIGIBLE` | journal event `VERIFIED_CLOSED / ELIGIBLE` | all nine predecessor closure gates plus journal validation | 1 |
| any terminal event | corrected terminal event | append-only event with valid `supersedesEventId` | determined by newest valid terminal event |

Detection alone never creates `VERIFIED_CLOSED`. `PATCHED_UNVERIFIED` is recorded in the private progress ledger rather than the terminal-event journal. A group cannot move from `OPEN` to a terminal journal event without that recorded patch state and both RED and GREEN evidence.

## Eleven-Group Adjudication

### False-positive dispositions

These five application owners are not modified:

| Owner | Frozen manifestations | Accepted current behavior |
|---|---:|---|
| `LocalLlmProcessManager` | 3 | named termination, rollback, and executable-selection fallback contracts |
| `ModelRuntimeHealthTracker` | 4 | named request-attempt trace/fingerprint fallback contracts |
| `PublicRequestBudgetGuard` | 2 | explicit body-executor rejection outcomes |
| `ApiTriadRoutePreflight` | 1 | deterministic fail-closed provider-path result |
| `HarmonySseRuntime` | 1 | terminal failure closes the stream lease |

The shared detector fix supplies one independent proof event per root-cause group. Multiple catch manifestations in one owner remain one rejected group.

### Genuine repair cycles

Run the six candidates serially in this order. Every cycle normally touches one application owner and one focused JUnit test. The shared audit contracts are changed only in the detector/closure infrastructure tasks, not opportunistically during a Java cycle.

#### Cycle 1: `StochasticParamSampler`

- Owner: `main/java/com/example/lms/ensemble/StochasticParamSampler.java`
- Test: `src/test/java/com/example/lms/ensemble/StochasticParamSamplerTest.java`
- RED: an injected runtime draw failure returns no profile, emits one redacted failure reason through the owner's existing ledger/trace seam, and exposes none of the injected value.
- Minimal patch: make the broad runtime branch use the same count-only failure family as the adjacent typed entropy failure while preserving `Optional.empty()`.
- Prohibited drift: sampling distribution, seed behavior, prompt pose, retry behavior, or success telemetry.

#### Cycle 2: `TrainRagIngestService`

- Owner: `main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`
- Test: `src/test/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestServiceTest.java`
- RED: an invalid timestamp still canonicalizes to the existing safe fallback while emitting only a count/reason code and never the raw value.
- Minimal patch: add one redacted invalid-timestamp breadcrumb at the existing owner seam.
- Prohibited drift: training JSONL authority, metadata shape, ingest acceptance, current-time fallback, database state, or vector promotion.

#### Cycle 3: `ScoringRunner`

- Owner: `main/java/com/example/lms/tools/ScoringRunner.java`
- Test: `src/test/java/com/example/lms/tools/ScoringRunnerTest.java`
- RED: malformed encoding or bounded source-read failure deterministically invalidates or lowers the affected fixture's score and records a repository-relative token plus reason code, without an absolute path or exception body.
- Minimal patch: add one tooling diagnostic through the existing scoring result path while preserving the null sentinel.
- Prohibited drift: scoring weights, thresholds, fixture enumeration, or production runtime behavior.

#### Cycle 4: `RagControlRuntimeAdapter`

- Owner: `main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java`
- Test: `src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java`
- RED: a nonnumeric nonnegative-integer field remains zero and cannot become positive lineage/proof while one count-only parse-fallback reason is visible.
- Minimal patch: record the existing zero fallback through the adapter's redacted trace seam.
- Prohibited drift: runtime-input schema, hold classification, CFVM behavior, AutoLearn behavior, or RAG arbitration.

#### Cycle 5: `TrustedProxyPolicy`

- Owner: `main/java/com/example/lms/web/TrustedProxyPolicy.java`
- Test: `src/test/java/com/example/lms/web/ClientOwnerKeyResolverTest.java`
- RED: malformed IPv6 input fails closed, never trusts forwarded ownership, and records only a bounded reason/count with no address or header value.
- Minimal patch: add one overwrite-style or rate-bounded diagnostic at the malformed-IPv6 branch while preserving `false`.
- Prohibited drift: CIDR parsing, proxy trust precedence, forwarded-header semantics, owner-key derivation, or raw IP logging.

#### Cycle 6: `BraveSearchService`

- Owner: `main/java/com/example/lms/service/web/BraveSearchService.java`
- Test: new focused `src/test/java/com/example/lms/service/web/BraveSearchServiceResponseShapeTest.java`, unless the live preimage proves the existing outbound-header test can host the behavior without mixing contracts.
- RED: malformed response shape returns invalid, follows the existing fail-soft classification, emits no provider-success claim, and records only a reason/count without body content.
- Minimal patch: add one response-shape breadcrumb at the existing provider trace seam.
- Prohibited drift: outbound calls, headers, credentials, endpoint selection, result synthesis, timeout/QPS behavior, or provider fallback precedence.
- Lane-local HOLD: if the behavior cannot be proven within one owner plus one focused test and one existing trace-contract file, hold only this group as `evidence_needed`; retain all prior verified closures.

## TDD And Cycle Protocol

Each genuine group executes this exact protocol:

1. verify source/test preimages and the predecessor group identity;
2. run the closest focused baseline test;
3. add one behavior-level or executable structural RED test that names the break;
4. run that test and require failure for the intended missing breadcrumb/diagnostic, not for compilation or fixture setup;
5. capture the RED summary and SHA-256;
6. apply the smallest owner-local patch;
7. rerun the focused test and require GREEN;
8. mentally and, where cheap, mechanically mutate/remove the new outcome so the test would fail;
9. rerun the shared catch-contract projection and require the exact predecessor fingerprint to disappear or become an accepted bounded transformation;
10. run the affected test class, root `compileJava`, sourceSet/dependency gates, count-only secret scan, and duplicate-owner check;
11. record `PATCHED_UNVERIFIED` in the private progress ledger, validate all evidence, then append the terminal `VERIFIED_CLOSED` journal event;
12. regenerate the transactional ledger bundle and verify that exactly one new group is counted closed;
13. recheck all declared preimages not owned by the completed cycle before starting the next group.

No later cycle starts while the current group has an unresolved RED, compile failure, preimage mismatch, secret hit, duplicated owner, or invalid journal event.

## Isolation And Source Authority

The implementation uses the existing isolated worktree only after verifying that it still matches the canonical Desktop targets and has no overlapping writer. The canonical Desktop root remains the final integration and verification owner.

Before the first application-source or test mutation:

- freeze one redacted EvidenceSnapshot with at most twenty rows;
- run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`;
- require order-stable `APPLY` with score at least 50;
- verify worktree list, branch, HEAD, index lock, staged paths, PatchDrop inventory, source-edit leases, target preimages, and Java 17;
- acquire the existing source-edit lease with an in-memory owner ID;
- recheck every declared preimage immediately after lease acquisition.

The parent Codex owns all edits, integration, verification, and final judgment. Read-only agents may map or review but do not write. The lease is released in `finally`, including on HOLD or failed verification.

No task stages, commits, pushes, deploys, deletes user work, resets Git, or copies whole files over dirty canonical targets.

## Privacy And Safety

- Journal and public artifacts contain only repository-relative paths, symbols, counts, reason codes, state, and hashes.
- No raw prompt, provider response, search result, body, timestamp input, IP address, header, credential, environment value, URL, absolute/UNC path, or exception body is recorded.
- Trace additions use stable allowlisted reason keys and overwrite-style or bounded-count behavior where a hot path could repeat.
- Brave verification is offline and deterministic. `providerAttempt=not_observed` remains explicit.
- Supabase remains `not_run`; no project-scoped state is required.
- Browser and Computer remain `not_run` unless a later live diff changes a rendered UI or Windows-GUI owner. None is currently declared.
- Secret scanning covers every changed source/test/script/build target plus all public artifacts and reports only hit counts.

## Failure And HOLD Contract

| Failure | Result |
|---|---|
| predecessor ledger/baseline/hash mismatch | repository-wide wave HOLD; no disposition can be linked safely |
| target or test preimage changed | hold only the overlapping cycle; continue no dependent write |
| detector consumers disagree after consolidation | detector-contract HOLD; no application repair credit |
| journal schema, hash, event chain, proof path, or disposition invalid | preserve prior artifacts and HOLD the affected event |
| exact predecessor fingerprint still emitted after claimed closure | `PATCHED_UNVERIFIED`; zero credit |
| focused RED fails for the wrong reason | repair the test setup or HOLD; do not patch production |
| focused GREEN or affected boundary fails | `PATCHED_UNVERIFIED`; zero credit and no next cycle |
| new secret-pattern hit | REJECT the unsafe evidence, discard the public artifact, and hold the affected cycle |
| duplicated route/helper/component/prompt boundary | HOLD the affected cycle |
| sourceSet, LangChain4j purity, or compile gate fails | zero credit; preserve first error |
| Brave proof exceeds the declared three-file boundary | lane-local `evidence_needed` for Brave only |
| unrelated baseline test failure prevents causal proof | lane-local HOLD with exact first failure and one verification action |

`repositoryWideHold=true` is used only for predecessor identity loss, overlapping writers across every target, or a detector/producer failure that makes every disposition untrustworthy. All other holds remain lane-local.

## Verification Ladder

Run the narrowest surface first and stop on the first causal failure:

1. shared catch-contract unit tests;
2. harmony-report and toolbox agreement tests on the same fixtures and all eleven owners;
3. quantitative-producer journal/state/supersession/rollback tests;
4. focused JUnit RED/GREEN for the current owner;
5. full current-owner test class;
6. regenerated harmony report, quantitative audit, baseline, ledger, metrics, and source-health scorecard;
7. repeat the exact audit/scorecard generation and compare semantic fields and ordered group/status rows;
8. `checkSourceSetHygiene` and `checkLangchain4jVersionPurity`;
9. root `compileJava` with Java 17 and Desktop-isolated Gradle cache/output settings;
10. affected module/classes only if a changed owner crosses that module boundary;
11. changed-target `git diff --check`;
12. declared-target scope comparison;
13. changed-target and public-artifact count-only secret scan;
14. duplicate FQCN/component/route/helper/prompt-boundary check;
15. final canonical Desktop rerun after target postimages match the isolated verified postimages.

The final report records exact commands, exit codes, test counts, artifact hashes, original and current baseline IDs, event IDs, group statuses, `verifiedClosedRootCauseGroups`, closure target gap, staged count, lock/lease state, and unobserved external lanes. It contains no long raw logs.

## Deliverables

- this approved design;
- an implementation plan under `docs/superpowers/plans/`;
- the shared catch-contract owner and focused tests;
- the closure journal contract and producer tests;
- scorecard validation of the closure-history linkage;
- one frozen eleven-group intake package;
- five validated false-positive events;
- up to six independently validated closure events;
- six minimal Java/test deltas, subject to lane-local HOLD rules;
- regenerated transactional public artifacts;
- one final Desktop evidence report and progress ledger.

## Acceptance Criteria

1. The written predecessor ledger, baseline, and metrics identities match the frozen approved intake.
2. Both harmony consumers use one brace-aware catch-contract owner and produce equal catch classifications for identical inputs.
3. The five named application owners receive no application-source change and appear as `REJECTED_FALSE_POSITIVE / REJECTED_BY_CONTRACT` with zero repair credit.
4. Every genuine group has an independent stable target preimage, scoped diff, RED, GREEN, fingerprint disposition, gate proof, and journal event.
5. No group is counted from detection, lines changed, catch manifestations, test count, or prose.
6. The producer accepts valid terminal events, rejects malformed or incoherent events, preserves prior outputs on failure, and recomputes nonzero `verifiedClosedRootCauseGroups` honestly.
7. A repeated unchanged run produces identical ordered issue/group/status rows and semantic hashes; timestamps do not affect semantic identity.
8. All applicable focused tests, sourceSet, dependency, compile, diff, duplicate-owner, secret, and Desktop gates pass for each credited group.
9. Public evidence has zero secret-pattern hits and no raw sensitive values.
10. Browser, Computer, provider, model-answer, runtime-answer, and Supabase claims remain `not_run` or `not_observed` unless fresh evidence from that surface is actually collected.
11. If all six cycles pass, the wave reports exactly six new `VERIFIED_CLOSED` groups, five rejected false positives, and a closure target gap of 894.
12. If any cycle holds, the report preserves earlier independently proven closures and reports the exact held group, first blocking rule, evidence, independent work completed, and one next verification action.
13. The approximately-1,000 parent goal remains active after this wave.
