# Current-Tree Structural Debt Ledger Design

Date: 2026-08-31

## Objective

Create the missing canonical producer for demo-1 quantitative source-audit evidence and a deterministic structural/design debt ledger. The producer must preserve the current dirty Desktop worktree as the authoritative baseline, identify evidence-backed repair candidates without inflating their count, and give later safe-patch cycles a stable way to prove that approximately 1,000 distinct structural/design defects were actually closed.

This is the first sub-project of the larger repair goal. Completing this design does not claim that any ledger item or the full approximately-1,000-item goal is already fixed.

## Approved Direction

Use the evidence-ledger-first approach.

Do not begin by splitting the largest classes or applying bulk formatting, catch rewriting, import cleanup, or static-analysis suggestions. First restore the missing audit-production contract, freeze the live baseline, and emit a ranked ledger whose entries have explicit evidence and falsifying proof commands. Later sub-projects consume that ledger in small verified patch cycles.

The approved count target is approximately 1,000 independently verified closures, interpreted as a target band of 900 through 1,100 `VERIFIED_CLOSED` root-cause groups. Lines changed, files touched, warnings emitted, tests added, and repeated manifestations of one root cause do not independently count as fixes.

## Current Evidence

The live Desktop root is `C:\AbandonWare\demo-1\demo-1\src` on branch `codex/owned-runtime-browser-restart` at observed HEAD `0796a3c`.

The approved baseline is the current worktree, not clean HEAD. Intake observed:

- 2,057 Git status entries: 798 modified, 171 deleted, and 1,088 untracked;
- 891 changed paths under active root `main/java`;
- 14 changed paths under `app/src/main/java_clean`;
- modified root and app Gradle build files;
- no index lock, active source-edit lease, or top-level PatchDrop patch;
- Java 17.0.13 and Gradle 8.7;
- active source roots `main/java`, `main/resources`, `app/src/main/java_clean`, and `app/src/main/resources`;
- inactive-present `app/src/main/java`;
- LangChain4j purity at exactly `1.0.1`;
- successful `checkSourceSetHygiene`, `compileJava`, and `:app:classes`;
- 2,121 Java files observed by the fresh harmony scan;
- 16 active files over 2,000 lines;
- 41 runtime cross-subsystem files over 1,000 lines;
- 18 broad catches estimated to lack a nearby breadcrumb;
- 12 duplicate FQCNs observed by the app packaging task, with zero kept in the packaged app;
- two untriaged cancellation-risk signals in `QueryTransformer`;
- zero unordered aspects and aspect-order coverage of 1.0;
- zero count-only secret-pattern hits in the fresh scoped scans.

The current structural score is not a valid raw quality score. `sourceHealthScorecard` expects `verification/dynamic-rag-quant-audit-metrics.json`, but the repository contains no production task or script that creates it. The scorecard accepts the absent file as an optional input, then records `quantMetrics=missing`, which collapses multiple component scores to zero. Tests create fixture copies only; they are not a runtime producer.

## Existing Seams

Reuse and connect the repository's existing evidence surfaces:

- `build.gradle.kts` owns `checkSourceSetHygiene`, `harmonyPressureReport`, `testTreeContaminationReport`, `sourceHealthScorecard`, and `sourceScoreReport`.
- `app/build.gradle.kts` owns duplicate-FQCN discovery and package exclusion.
- `scripts/harmony_pressure_report.py` owns cross-subsystem, catch, AOP-order, and manual-prompt candidate metrics.
- `scripts/test_tree_contamination_report.py` owns active-source/test alignment metrics.
- `scripts/source_health_scorecard.py` consumes canonical metric artifacts and owns the strict evidence-adjusted score.

Add one missing producer rather than a second scoring framework. Keep the producer read-only with respect to application source and external systems.

## Scope

In scope for this sub-project:

- a canonical quantitative-audit producer;
- deterministic structural/design issue identification;
- stable issue and root-cause-group identities;
- a current-worktree baseline manifest;
- a bounded structural/design debt ledger;
- explicit duplicate-FQCN source-versus-package semantics;
- Gradle task ordering from raw evidence through the scorecard;
- focused producer tests and current-checkout verification;
- source isolation that reproduces the approved dirty baseline before implementation.

Out of scope for this sub-project:

- closing the generated application-source issues;
- splitting `ChatWorkflow` or another large class;
- changing RAG behavior, prompts, provider routing, credentials, resources, or database state;
- starting or stopping application, model, browser, or unrelated Windows processes;
- claiming Browser, Computer, provider-wire, Supabase, semantic-answer, or full-runtime success;
- committing, pushing, deploying, or mutating Git history without separate authority;
- changing Spring Boot or any LangChain4j version.

## Source Isolation

Implementation occurs in a dedicated worktree created after written-spec approval and implementation planning. The planned branch is `codex/current-tree-structural-debt-ledger`; the planned location is `C:\AbandonWare\worktrees\codex-current-tree-structural-debt-ledger`.

The isolation workflow must:

1. Reconfirm the canonical root, branch, HEAD, worktrees, status counts, index lock, PatchDrop inventory, and active leases.
2. Freeze a manifest over every tracked or non-ignored untracked path in the current worktree. Each row contains repository-relative path, Git state, size, and SHA-256; it contains no file contents or raw alternate/UNC roots.
3. Create the dedicated worktree at the same HEAD without committing the dirty source.
4. Apply the full tracked binary diff from HEAD, including deletions.
5. Copy the exact NUL-delimited set from `git ls-files --others --exclude-standard`, preserving repository-relative paths.
6. Exclude ignored build outputs, caches, runtime logs, and generated verification artifacts unless a later preflight explicitly declares one as an input.
7. Recompute the tracked/non-ignored manifest in the isolated worktree and require equality with the canonical manifest.
8. Fail closed on an existing unknown target worktree, manifest mismatch, path traversal, reparse-point escape, index lock, active source lease, top-level PatchDrop patch, or changed canonical preimage.

The canonical Desktop root remains the final verification area. Isolation does not authorize staging, committing, pushing, deleting canonical files, or applying a PatchDrop bundle.

## Components

### 1. Quantitative Audit Producer

Add `scripts/dynamic_rag_quant_audit.py` as the single canonical producer. It reads only declared active roots and existing generated evidence. Its normal outputs are:

- `verification/dynamic-rag-quant-audit-metrics.json`;
- `verification/structural-design-baseline.json`;
- `verification/structural-design-debt-ledger.jsonl`.

The producer accepts explicit repository root, active Java roots, input artifact paths, output paths, and a configurable candidate cap. Defaults must match the Gradle task. Tests may inject a fixed generation time; production uses the current UTC time.

The producer must not invoke providers, databases, Browser, Computer Use, model runtimes, network endpoints, or shell commands. Gradle owns prerequisite task execution and passes generated artifact paths to the producer.

### 2. Duplicate-FQCN Packaging Evidence

Extend the existing app duplicate-FQCN owner to expose machine-readable count/hash evidence from the same algorithm that already determines generated and hard exclusions. Do not implement a second Kotlin/Python interpretation of packaging rules.

The app owner writes `layout.buildDirectory.file("reports/dup-fqcn-evidence.json")`; host-specific build-directory routing therefore keeps the physical output isolated without hardcoding a host path. The root Gradle task passes that provider-backed file to the producer.

The evidence distinguishes:

- `duplicateFqcnSourceCollisionCount`: raw same-FQCN collisions across canonical root and `java_clean`;
- `duplicateFqcnGeneratedExcludeCount`: collisions covered by generated exclusions;
- `duplicateFqcnHardExcludeCount`: collisions covered by the safety-net list;
- `duplicateFqcnPackagedActiveCount`: collisions that remain in the packaged app.

For backward compatibility, `duplicateFqcnActiveCount` in canonical quant metrics means `duplicateFqcnPackagedActiveCount`, not raw source collisions. Current evidence expects this value to be zero while preserving the raw collision count as structural debt.

### 3. Gradle Evidence Chain

Add a `dynamicRagQuantAudit` task. The dependency chain is:

```text
duplicate-FQCN evidence
        +
harmonyPressureReport
        +
testTreeContaminationReport
        -> dynamicRagQuantAudit
        -> sourceHealthScorecard
```

`sourceHealthScorecard` must no longer silently run without its canonical quant input. A direct scorecard invocation either generates the input through task dependencies or fails with a specific missing-input contract; it must not emit a misleading near-zero score as though the input were current.

Specifically, the Gradle task generates the input through dependencies. Direct Python execution of `source_health_scorecard.py` without a current canonical quant artifact exits nonzero with reason code `quant-metrics-missing-or-stale` and does not replace the prior scorecard.

### 4. Producer Tests

Add `scripts/test_dynamic_rag_quant_audit.py`. Use temporary fixture repositories only. Tests must not read or alter the user's canonical source tree.

## Canonical Metrics Contract

`dynamic-rag-quant-audit-metrics.json` contains the fields already required by the scorecard plus explicit provenance:

- `schemaVersion`, `generatedAt`, `baselineId`, and semantic artifact hash;
- `activeJavaFileCount`, `activeJavaLocTotal`, `activeJavaLocP95`, and `largeActiveFilesOver2000`;
- the four explicit duplicate-FQCN counts and backward-compatible `duplicateFqcnActiveCount`;
- `secretPatternHitCount` as a count only;
- `harmonyPressureSummary` copied from a current validated harmony artifact;
- `testTreeContamination` copied from a current validated test-tree artifact;
- `ledgerSummary` with total, emitted, eligible, review-only, overflow, and per-category counts;
- explicit `evidence_needed` status objects for optional provider or Supabase inputs that are not current, never fabricated success values.

Freshness uses the existing scorecard contract. The producer rejects required inputs that the scorecard would classify as missing, malformed, undated, or stale.

## Baseline Contract

`structural-design-baseline.json` contains:

- schema version and generation time;
- canonical-root hash and path length, never a raw alternate mapping;
- HEAD hash and branch name;
- active-sourceSet names and configuration hash;
- tracked/modified/deleted/untracked counts;
- active file count and total bytes;
- ordered path-state-content hashes;
- aggregate `baselineId` computed from the ordered rows;
- count-only secret scan result;
- ignored-artifact policy version.

The ledger records `baselineId`. A later repair cycle must not close an item against a different baseline without rerunning detection and recording an explicit supersession relationship.

## Ledger Contract

Each JSONL row contains:

- `schemaVersion`;
- `issueId`;
- `rootCauseGroupId`;
- `baselineId`;
- `category`;
- `severity`;
- `activeSourceSet`;
- `path` as a canonical repository-relative path;
- `symbol` when deterministically identifiable;
- `evidenceReason` as an allowlisted reason code;
- `evidenceFingerprint` as SHA-256;
- bounded numeric evidence such as line, dependency, catch, or subsystem counts;
- `suspectedBoundary`;
- `fixEligibility`;
- `proofCommand` from an allowlisted command family;
- `status`;
- `supersedes` when applicable.

The ledger stores no source snippets, prompts, responses, credentials, environment values, command lines, absolute source paths, raw URLs, or external payloads.

Allowed `fixEligibility` values are `ELIGIBLE`, `REVIEW_REQUIRED`, `EXTERNAL_EVIDENCE_REQUIRED`, and `REJECTED_BY_CONTRACT`. Only `ELIGIBLE` rows can enter an implementation plan without a new evidence decision.

## Stable Identity And Ordering

Normalize paths to Git canonical casing with `/` separators. Normalize symbols and evidence keys without locale-dependent transforms.

`issueId` is derived from the full SHA-256 of:

```text
schemaVersion | category | canonicalRelativePath | symbol | normalizedEvidenceKey
```

`rootCauseGroupId` is derived from the boundary owner plus normalized root-cause key. Different manifestations of one owner defect share the group ID and earn at most one closure credit.

Order rows by severity rank, eligibility rank (`ELIGIBLE`, `REVIEW_REQUIRED`, `EXTERNAL_EVIDENCE_REQUIRED`, `REJECTED_BY_CONTRACT`), category, case-folded canonical path, symbol, and full issue hash. Repeated runs on unchanged inputs must produce identical issue IDs, group IDs, ordering, category counts, and semantic artifact hash. `generatedAt` is excluded from the semantic hash.

Duplicate issue IDs, duplicate semantic keys, unstable ordering, or conflicting group ownership are hard failures.

## Initial Evidence Categories

The first producer version may emit only deterministic categories with explicit tests:

- `FILE_SIZE_CONCENTRATION` for active Java files over the declared 2,000-line threshold;
- `CROSS_SUBSYSTEM_CONCENTRATION` for runtime files over 1,000 lines that match the declared subsystem threshold;
- `BROAD_CATCH_NO_BREADCRUMB` from the existing harmony algorithm;
- `DUPLICATE_FQCN_SOURCE_COLLISION` from app-owned duplicate evidence;
- `DUPLICATE_FQCN_PACKAGED_ACTIVE` when a collision survives packaging exclusions.

Manual prompt candidates and heuristic subsystem hits may be recorded with `fixEligibility=REVIEW_REQUIRED`, but they cannot count as verified defects or closures until an executable contract promotes them. New categories require their own design or a reviewed extension of this specification; they are not added merely to reach 1,000.

The currently observed cancellation-bypass and silent-catch signals remain candidate categories for a later extension. They are not emitted in version 1 because their present owners do not expose the required structured, machine-readable evidence to this producer. The missing-producer defect itself is proven by the pre-change design evidence and implementation verification; the newly installed producer must not synthesize a self-referential ledger row after that defect has disappeared.

## Candidate Bound And Overflow

The default ledger cap is 1,100 candidate rows, matching the approved upper target band. Selection is deterministic after complete category counting and ranking, with eligible candidates selected before review-only or external-evidence rows at the same severity.

If the scan finds more than the cap, metrics record total candidates, emitted candidates, overflow count, category totals before truncation, and `truncated=true`. Overflow is not silently discarded or reported as fixed. If fewer than 900 eligible candidates exist, the producer reports the honest count and `targetGap`; it does not split one defect, lower thresholds, or promote review-only warnings to manufacture volume.

## State And Counting

Allowed statuses are:

- `OPEN`;
- `HOLD`;
- `REJECTED_FALSE_POSITIVE`;
- `SUPERSEDED`;
- `PATCHED_UNVERIFIED`;
- `VERIFIED_CLOSED`.

Only a root-cause group in `VERIFIED_CLOSED` counts toward the approximately-1,000-fix goal. Closure requires all of the following:

1. pre-change evidence on the frozen baseline;
2. a declared active owner and target preimage;
3. a scoped source diff linked to the group;
4. focused RED-to-GREEN or an equivalent behavior-preserving structural contract;
5. disappearance or accepted transformation of the original evidence fingerprint;
6. applicable sourceSet, dependency-version, compile, and affected-boundary gates;
7. count-only secret scan with zero new hits;
8. no duplicated implementation, route, helper, Spring component, or prompt boundary;
9. final Desktop proof for the affected surface.

One patch may close multiple groups only when every group has independent pre-evidence and proof. Multiple issue rows in one group still count once.

## Repair-Cycle Interface

Later plans consume the ledger in deterministic priority order:

1. packaged-active FQCN or safety-contract failures;
2. missing evidence-production contracts;
3. high-severity owner ambiguity;
4. bounded cancellation or silent-failure defects;
5. cross-subsystem extraction candidates with executable contracts;
6. file-size concentration only when a real boundary can be extracted without behavior drift.

Each cycle owns one intent and normally touches at most three source files. A cycle stops on changed preimage, ownership ambiguity, failing baseline, external proof need, or scope expansion. It never performs bulk formatting or opportunistic unrelated cleanup.

## Error And HOLD Contract

The producer exits nonzero and preserves prior outputs when any of these occurs:

- missing or escaped active root;
- malformed required input artifact;
- stale prerequisite artifact;
- duplicate or unstable issue identity;
- inconsistent duplicate-FQCN counts;
- secret-pattern match in a public artifact;
- absolute, UNC, traversal, or reparse-escaped path in output;
- output replacement failure;
- semantic nondeterminism on the same declared baseline.

Write all outputs to sibling temporary files and validate them before publication. Every artifact carries one `auditRunId` and the hashes of the other artifacts. Before replacement, copy existing outputs to bounded sibling rollback files; replace outputs in a fixed order; publish metrics last as the commit record. On a replacement failure, restore every prior output and fail closed. Consumers reject mismatched run IDs or cross-artifact hashes. On failure, emit a short reason code and counts; do not emit raw source or exception bodies containing private values.

Application-source mutation later requires the repository's three-way source-edit preflight and existing source-owner guard. This producer sub-project adopts the same preflight conservatively before modifying build or executable repository scripts, even though the mandatory trigger explicitly names application source.

## Browser And Computer Evidence Boundaries

Browser and Computer remain explicit supporting lanes, not edit authorization.

This sub-project has no UI or Windows-GUI behavior change, so its completion does not require Browser or Computer execution. Later cycles must add Browser DOM proof when they affect `/chat-ui`, stream/cancel behavior, rendered evidence, or browser-visible diagnostics. Computer Use is added only when a required Windows GUI state cannot be established through source, shell, or Browser evidence.

Browser or Computer evidence never substitutes for source, Gradle, provider-wire, semantic-answer, or Supabase proof. No Supabase mutation is part of this design.

## Verification Design

Focused producer tests cover:

- active-root validation and inactive-root rejection;
- deterministic baseline and semantic hashes;
- stable IDs and ordering across repeated runs;
- root-cause grouping and one-credit counting;
- exact threshold boundaries;
- duplicate-FQCN raw/generated/hard/kept semantics;
- metrics schema and cross-artifact run/hash linkage;
- stale or malformed prerequisite rejection;
- cap, overflow, and target-gap behavior;
- review-only candidates excluded from eligible closure counts;
- no raw snippets, secrets, absolute paths, URLs, prompts, or responses in artifacts;
- atomic output preservation on failure;
- fixed-time test injection without production timestamp drift.

Repository verification then runs, in order:

1. `python -X utf8 scripts\test_dynamic_rag_quant_audit.py`;
2. the focused existing harmony, test-tree, source-health, and app sourceSet contract tests;
3. `gradlew.bat dynamicRagQuantAudit sourceHealthScorecard` with Desktop split outputs and isolated Gradle caches;
4. a second unchanged-input audit proving stable IDs, ordering, and semantic hash;
5. `gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test`;
6. `git diff --check`;
7. a changed-file count-only secret scan.

The verification report must separate producer-contract success from application-source repairs, Browser/Computer proof, provider attempts, model answers, and Supabase state.

## Completion Criteria For This Sub-Project

This first sub-project is complete only when current evidence proves all of the following:

1. The approved dirty worktree baseline is reproduced in isolation with matching non-ignored path states and hashes before edits.
2. One canonical producer creates current quant metrics, baseline, and ledger artifacts atomically.
3. Direct `sourceHealthScorecard` execution cannot silently use missing quant metrics.
4. Duplicate-FQCN source collision and packaged-active meanings are distinct and machine-readable from the existing app owner.
5. Stable issue IDs, root-cause groups, ordering, counts, cap behavior, and semantic hashes pass focused tests.
6. Generated public artifacts contain only allowlisted redacted evidence and zero secret-pattern hits.
7. Focused tests, sourceSet hygiene, LangChain4j purity, `compileJava`, and `:app:classes` pass from isolated caches.
8. No application behavior, prompt, provider, resource, database, Browser, Computer, external process, unrelated dirty file, Git history, or PatchDrop state is changed.
9. The final report states the honest eligible candidate count and remaining gap; it does not claim any candidate is fixed merely because it was detected.

After these criteria pass, the full goal remains active. The next approved sub-project selects the highest-priority eligible root-cause groups and begins verified repair cycles until 900 through 1,100 distinct groups are `VERIFIED_CLOSED`, or current evidence proves that the requested range cannot be reached without inventing or duplicating defects.
