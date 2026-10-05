# Astra refactor phase 1 — 2026-10-05

Status: **DONE**. WP-C2, WP-W1a and WP-T are verified against the phase-1 acceptance criteria. The selected test set retains one proven baseline failure; zero new failures were introduced. No later work package was started.

Task: `codex-astra-refactor-phase1-70d1df07`. Evidence: [EVIDENCE.md](../../data/agent-handoff/codex-astra-refactor-phase1-70d1df07/EVIDENCE.md). Recovery: `data/agent-handoff/codex-autonomy/codex-astra-refactor-phase1-70d1df07/`.

## Extracted responsibilities

- `ChatApiController.sessionTraceBundle` now delegates in one line to the package-private final `ChatTraceBundleResponseBuilder.build`. The body was copied exactly apart from two explicit dependency substitutions: a build-version supplier and the existing Controller logging callback. The supplier preserves the original evaluation point inside the catch boundary. The builder has no Spring state. The Controller prefix/suffix outside this method, including the other owner's quota changes, are byte-identical to the reconciled task preimage.
- `ChatWorkflow.directNumberOnlyMultiplicationLiteral` delegates to `DeterministicLiteralAnswers.numberOnlyMultiplication`. The body was copied verbatim, with only the method signature changed. The new public final class has no Spring or workflow state. The existing caller remains unchanged. Hashes of the workflow prefix and suffix prove that no other workflow method changed.
- `tools/build_error_secret_mask.py` owns the identical `SECRET_FRAGMENT_RE`. The evaluated pattern was 335 characters in both original tools; effective flags were 34 (`IGNORECASE | UNICODE`). Both tools retain the exported name and their existing redaction call sites and replacement marker. Package imports use the sibling module; direct scripts and file-location callers load the same sibling file by its path without changing `sys.path`.
- A narrowly scoped checkpoint exception recognizes three exact synthetic fixture strings in `tools/test_build_error_secret_mask.py`. This necessary scope expansion changes 8 production lines and adds a dedicated regression test file. Changed fixtures, other paths and adjacent credential-shaped values still fail the guard. Existing foreign edits to the checkpoint script were preserved against a fresh preimage.

## Source size

Counts are actual working-file bytes and logical lines, before this task and after its changes.

| File | Before bytes / lines | After bytes / lines |
|---|---:|---:|
| `ChatApiController.java` | 295,316 / 5,463 | 289,241 / 5,381 |
| `ChatWorkflow.java` | 735,116 / 14,284 | 733,084 / 14,239 |

The workflow diff has one hunk: `@@ -10776,46 +10776 @@`. Its method signature is still at line 10775. The Controller's task-owned diff has one hunk: `@@ -5243,83 +5243 @@`; the method signature remains at 5241. LF line endings were preserved. Both new classes contain the extracted implementations.

## Tool classification

| Surface | Classification | Contract retained |
|---|---|---|
| Two original secret-fragment regex definitions | SAME_PURPOSE_DUPLICATE → SHARED | Identical pattern, flags and replacement marker |
| `tools/build_error_miner.py` | OVERLAP | Explicit file/directory/ZIP inputs; normalized context; five-example cap; multiple output formats |
| `tools/build_error_pattern_scanner.py` | OVERLAP | Conventional log discovery; category counts; first short sample; latest JSON and history |
| `tools/build_error_guard.py` | OVERLAP | Remediation catalog and ignore filtering; matched-problem exit 2 |
| `tools/build_error_scan.py` | OVERLAP | External pattern schema; occurrence/severity records; hashed capture summaries and history |
| `scripts/build_error_mitigator.py` | DISTINCT | Applies configured Gradle-file mitigations; hash-only exception summaries |

All five entry points remain. No tool was removed or merged. Scanner CLI verification ran in a temporary synthetic directory because the existing scanner has no help/dry-run parser and scans its current directory.

## Verification

| Verification | Fresh observed result |
|---|---|
| Original six Java suites before any application edit | 39 tests; 38 pass; 1 failure; Gradle exit 1 |
| New literal characterization through original workflow | 28/28 pass; exit 0 |
| Three service suites after extraction | 44/44 pass; exit 0 |
| Original six suites plus the new literal suite after extraction | 67 tests; 66 pass; same 1 failure; new failures 0; exit 1 |
| New trace characterization through original Controller / extracted builder | 9/9 pass in each run; exit 0 |
| Final original six suites plus both new suites | 76 tests; 75 pass; same 1 failure; new failures 0; exit 1 |
| Final `compileJava --console=plain` | exit 0 |
| New and existing tool unittest suites before / after | 13/13 pass in each run; exit 0 |
| Synthetic tool output comparison | Five scenarios, identical serialized bytes and key order |
| Miner help / scanner synthetic CLI | Both exit 0; stderr empty |
| Guard regression | RED: 5 tests, 4 errors; GREEN: 34/34 pass including existing guard tests |

The pre-existing failure is `ChatSessionTraceDetailTest.existingTraceExposureRequiresBothOwnerAndOperator`: expected 404, actual 403. It was observed before this task's changes and repeated afterward. No authentication behavior was changed. The selected suite is not fully green; no full test suite was run.

The literal cases preserve null/blank rejection, signs, whitespace, multiplication symbols, full-width rejection, negation, decimals, chained expressions and the nine-digit operand cap. The maximum accepted product fits in a Java long; overlong inputs are rejected before `Math.multiplyExact`. No new overflow handling was introduced.

Trace characterization covers schema, headers, ZIP entry order, default JSON key order, checksums, exact 262,144-byte acceptance, 262,145-byte rejection, a real 201-event ring limited to 200 entries, already-hashed ID preservation, untrusted-ID rejection, legacy diagnostics and serialization failure. The before/after archive SHA-256 is `24a2933e79c44eb27ccd4da3a1ac1038e27f5df4b670f9852b67468c98dbe3c6`. This probe freezes `exportedAt`, uses an explicitly sorted mapper to avoid JVM-randomized `Map.of` order, and normalizes only ZIP DOS timestamp fields. It establishes equal archive bytes under those controls; raw wall-clock ZIP bytes were not claimed equal. Default mapper key order is asserted separately.

Official Java 17 references checked on 2026-10-05: [Pattern](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html), [Math.multiplyExact](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Math.html). Local build contracts remain Spring Boot 3.3.4 and LangChain4j 1.0.1; dependencies and configuration were not edited.

## Reconciled external drift

Gate0 found Controller sha12 `a84067c229a8`, differing from the directive pin `79216c106f58`, plus a live `codex-interview-quota-resume` lease. WP-C2 was held while the independent work completed. During continuation, the quota task's verified checkpoint preimage `codex-interview-turn-quota-resume-bdf0cf5a/implementation-green/before/2.bin` matched the original full pin, and its postimage matched the current Controller. The target method was byte-identical across that exact preimage and current file. Fresh ownership evidence showed no overlapping writer, so a new scoped lease/checkpoint safely resumed C2. Full evidence is in `resume-controller-reconciliation.json`.

The unchanged public route is `/api/chat/sessions/{id}/traces/{snapshotId}/html?format=bundle`. Event limit 200, existing hashed IDs, schema, 262,144-byte size cap, no-store ZIP response and 500 catch boundary remain in the extracted body.

NEXT candidate (not started): WP-A1 domain-classifier extraction, because it can isolate pure classification responsibilities without moving the interview final-release policy. It requires its own scoped objective and current evidence.

Live trace comparison: **NOT_RUN**. This is optional in W4; the shared runtime was not restarted, and no safe pre-refactor live bundle capture was available for a paired comparison. These tests and compilation do not prove deployment or live behavior. Live LLM calls, public-site submissions, commits and pushes remained zero.

PLUGIN_USAGE: Superpowers investigation/verification principles; one reused native Codex explorer for tool inventory, narrow guard review and C2 dependency/catch-boundary review (ACCEPT with the archive-digest comparison limitation subsequently checked); built-in web search for official Java 17 semantics; primary local AWX build-error mining once on a sanitized baseline failure summary (`other`, not a root-cause verdict). GLM, Devin, Browser, Computer, GitHub, Sites, Vercel and backup AWX were not invoked.
