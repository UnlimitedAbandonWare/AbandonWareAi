# Current-Tree Structural Debt Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the missing canonical quantitative-audit producer, freeze the approved dirty worktree as a reproducible baseline, and publish a deterministic structural/design debt ledger that can support honest progress toward 900–1,100 independently verified root-cause closures.

**Architecture:** Gradle captures Git state before generated evidence changes the worktree, runs the existing harmony, test-tree, and app-owned duplicate-FQCN evidence owners, then invokes one shell-free Python producer. The producer validates all declared inputs, scans only active Java roots, creates stable issue and root-cause identities, and transactionally publishes a baseline, JSONL ledger, and metrics commit record. `sourceHealthScorecard` consumes the linked bundle and refuses missing, stale, or incoherent quantitative evidence.

**Tech Stack:** Java 17, Gradle 8.7 Kotlin DSL, Python 3 standard library, `unittest`, PowerShell, Git porcelain v2 `-z`, SHA-256, Windows filesystem compare-and-swap checks.

**Spec:** `docs/superpowers/specs/2026-08-31-current-tree-structural-debt-ledger-design.md`

## Global Constraints

- The full user objective remains active: reach approximately 1,000 structural/design repairs only through 900–1,100 distinct `VERIFIED_CLOSED` root-cause groups. This plan installs the measuring and selection substrate; it does not claim any application-source closure.
- The approved baseline is the current dirty Desktop worktree, not clean `HEAD`. At planning time the spec hash is `36a20cebb8a8d844f2ce4aa92ec2a59362fae5e0598d06a8a78f597f15e371d1`; recheck it before execution.
- Reconfirm active roots from Gradle before edits. Expected roots are `main/java`, `main/resources`, `app/src/main/java_clean`, and `app/src/main/resources`; `app/src/main/java` remains inactive-present.
- Keep Spring Boot at the repository version and every `dev.langchain4j` dependency exactly at `1.0.1`.
- Preserve all unrelated modified, deleted, and untracked paths. Never clean, reset, normalize, or broadly reformat the worktree.
- Run the repository's single three-way source-edit preflight before modifying build logic or executable scripts. Exactly one frozen snapshot feeds `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; proceed only on order-stable `APPLY`.
- Use one dedicated worktree at `C:\AbandonWare\worktrees\codex-current-tree-structural-debt-ledger` on branch `codex/current-tree-structural-debt-ledger`. Fail closed if either target is already owned by an unknown worktree or branch.
- The producer may read files and declared generated inputs only. It must not invoke Git, a shell, providers, databases, Browser, Computer Use, models, network endpoints, or external processes.
- Browser and Computer are explicit supporting lanes only. This subproject changes no UI or Windows GUI behavior, so neither lane is part of its completion proof.
- No provider, Supabase, model-answer, wire-attempt, runtime-answer, or Browser success claim is allowed. Missing optional external evidence remains an explicit `evidence_needed` object.
- Do not add a dependency, stage, commit, push, deploy, delete canonical files, mutate a database, persist credentials, or change Git history without separate operation-level authority. Every task records a suggested commit message only.
- Generated evidence belongs under `verification/` or host-split `build/`; do not treat generated files as application-source repairs.

## File Structure

- Create `scripts/dynamic_rag_quant_audit.py`: the only canonical quantitative-audit producer, bundle validator, stable ledger builder, and transactional publisher.
- Create `scripts/test_dynamic_rag_quant_audit.py`: temporary-repository RED/GREEN tests for parsing, scanning, identities, counting, safety, freshness, and publication.
- Modify `scripts/harmony_pressure_report.py`: expose complete deterministic, snippet-free ledger evidence from its existing subsystem and catch algorithms.
- Modify `scripts/test_harmony_pressure_report.py`: contract tests for complete ledger evidence and stable sorting.
- Modify `app/build.gradle.kts`: publish duplicate-FQCN evidence from the exact existing packaging decision algorithm.
- Modify `scripts/test_app_build_sourceset_contract.py`: assert the app owner and evidence-output contract without introducing a second duplicate classifier.
- Modify `build.gradle.kts`: capture NUL-safe Git inputs, wire the evidence dependency chain, and make quantitative input mandatory for the Gradle scorecard path.
- Modify `scripts/source_health_scorecard.py`: reject missing, stale, malformed, or cross-artifact-incoherent quant bundles before any output write.
- Modify `scripts/test_source_health_scorecard.py`: prove the fail-closed scorecard contract and Gradle dependency chain.
- Generate, but do not hand-edit, `verification/dynamic-rag-quant-audit-metrics.json`, `verification/structural-design-baseline.json`, and `verification/structural-design-debt-ledger.jsonl`.

## Evidence Flow

```text
captureStructuralAuditGitState  :app:generateDupFqcnExcludes
             |                              |
             |                    dup-fqcn-evidence.json
             |                              |
             +---- harmonyPressureReport ---+
             +---- testTreeContaminationReport
                            |
                  dynamicRagQuantAudit
                     |       |       |
                  baseline  ledger  metrics (published last)
                                      |
                              sourceHealthScorecard
```

The three public audit artifacts use payload hashes, not impossible circular byte hashes. A payload hash excludes `generatedAt`, `auditRunId`, and `artifactLinks`; the metrics payload additionally excludes its self-describing `semanticArtifactHash`. Every artifact then carries the same deterministic `auditRunId` and the three payload hashes. The metrics file is replaced last and acts as the bundle commit record.

---

### Task 0: Reproduce the Approved Dirty Baseline in an Isolated Worktree

**Files:**

- Read: `AGENTS.md`
- Read: `docs/superpowers/specs/2026-08-31-current-tree-structural-debt-ledger-design.md`
- Read/run: `__patch_drop__/janitor_inventory.ps1`
- Create outside the canonical root: `C:\AbandonWare\worktrees\codex-current-tree-structural-debt-ledger`
- Create temporary hash manifests under a new `New-TemporaryFile` or `New-Item` temporary directory; do not place them in source roots.

**Interfaces:**

- Consumes: the canonical Desktop worktree, current `HEAD`, tracked binary diff, and exact non-ignored untracked path list.
- Produces: an isolated worktree whose pre-edit path/state/size/SHA-256 manifest equals the canonical manifest.

- [ ] **Step 1: Reconfirm instructions, target identity, sourceSet evidence, and blockers**

Run from `C:\AbandonWare\demo-1\demo-1\src`:

```powershell
$canonicalRoot = (Resolve-Path -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src').ProviderPath
$isolatedRoot = 'C:\AbandonWare\worktrees\codex-current-tree-structural-debt-ledger'
$topicBranch = 'codex/current-tree-structural-debt-ledger'
$specPath = Join-Path $canonicalRoot 'docs\superpowers\specs\2026-08-31-current-tree-structural-debt-ledger-design.md'
$specHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $specPath).Hash.ToLowerInvariant()
if ($specHash -ne '36a20cebb8a8d844f2ce4aa92ec2a59362fae5e0598d06a8a78f597f15e371d1') { throw 'approved-spec-changed' }
git -C $canonicalRoot rev-parse --show-toplevel
git -C $canonicalRoot branch --show-current
git -C $canonicalRoot rev-parse HEAD
git -C $canonicalRoot worktree list --porcelain
git -C $canonicalRoot status --short
if (Test-Path -LiteralPath (Join-Path $canonicalRoot '.git\index.lock')) { throw 'index-lock-conflict' }
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\janitor_inventory.ps1')
java -version
.\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity --no-daemon
```

Expected: canonical root matches exactly, Java reports major version 17, active roots match the specification, the inventory reports no blocking source lease and no active top-level patch, and LangChain4j purity passes. Any conflicting worktree, index lock, blocking lease, top-level patch, changed spec hash, or changed active root is a lane-local `HOLD` before worktree creation.

- [ ] **Step 2: Freeze the canonical manifest before any generated task runs**

Use `git ls-files --cached --others --exclude-standard -z` to enumerate the complete tracked plus non-ignored untracked set. Use `git status --porcelain=v2 -z --untracked-files=all` for state. For every path, reject absolute paths, `..`, drive-qualified paths, URI syntax, and reparse traversal; record only repository-relative canonical path, normalized state, byte size, and SHA-256. Deleted files use size `0` and the literal content marker hash `sha256("DELETED")`.

The manifest writer must sort by UTF-8 repository-relative path and emit canonical JSON. It must exclude only the versioned generated-artifact policy used later by the producer: host build/cache roots, runtime logs, the three audit outputs, harmony/test-tree outputs, source-health outputs, sibling `.tmp` files, and sibling `.rollback` files.

Record only counts and the final manifest SHA-256 in conversation evidence; do not print file contents or alternate mappings.

- [ ] **Step 3: Create the worktree at the live captured `HEAD`**

```powershell
$baselineHead = (git -C $canonicalRoot rev-parse HEAD).Trim()
if (Test-Path -LiteralPath $isolatedRoot) { throw 'unknown-target-worktree' }
if ((git -C $canonicalRoot branch --list $topicBranch).Trim()) { throw 'topic-branch-already-exists' }
git -C $canonicalRoot worktree add -b $topicBranch $isolatedRoot $baselineHead
```

Expected: the new worktree uses the exact captured `HEAD`; no commit or staging occurs.

- [ ] **Step 4: Reproduce tracked modifications, deletions, and binary content**

Create a temporary patch with Git's `--output` option, validate that its parent is the task temporary directory, and apply it inside the isolated worktree:

```powershell
$transferRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-structural-ledger-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $transferRoot | Out-Null
$trackedPatch = Join-Path $transferRoot 'tracked-baseline.patch'
git -C $canonicalRoot diff --binary --full-index --output=$trackedPatch HEAD -- .
git -C $isolatedRoot apply --binary --whitespace=nowarn $trackedPatch
if ($LASTEXITCODE -ne 0) { throw 'tracked-baseline-apply-failed' }
```

Do not use `git add`, `git commit`, `git reset`, or `git checkout`.

- [ ] **Step 5: Copy the exact NUL-delimited untracked set**

Read `git ls-files --others --exclude-standard -z` as bytes. Decode each non-empty UTF-8 path, normalize `/`, verify it stays below both roots, reject every reparse-point component, create only its parent directory, and copy with `Copy-Item -LiteralPath`. Do not copy ignored files or directory globs.

- [ ] **Step 6: Prove pre-edit manifest equality**

Recompute the same manifest in the isolated root. Require equal row count, equal ordered path/state/size/hash rows, equal aggregate hash, equal active-root file count, and equal active-root bytes. Also require `git -C $isolatedRoot rev-parse HEAD` to equal `$baselineHead`.

Expected: exact equality. On mismatch, stop with `baseline-manifest-mismatch`; retain the temporary manifests for diagnosis but make no source edits.

- [ ] **Step 7: Record authority boundary**

Suggested commit message: `chore: reproduce current structural audit baseline`. Do not stage or commit it. The worktree and branch are isolation artifacts only.

### Task 1: Pass the Single Three-Way Edit Gate and Freeze Target Preimages

**Files:**

- Read: `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`
- Declare targets: `build.gradle.kts`, `app/build.gradle.kts`, `scripts/harmony_pressure_report.py`, `scripts/test_harmony_pressure_report.py`, `scripts/source_health_scorecard.py`, `scripts/test_source_health_scorecard.py`, `scripts/test_app_build_sourceset_contract.py`, `scripts/dynamic_rag_quant_audit.py`, `scripts/test_dynamic_rag_quant_audit.py`
- Write the repository-prescribed preflight evidence only to its declared handoff/evidence location.

**Interfaces:**

- Consumes: one redacted `EvidenceSnapshot`, approved spec hash, isolated manifest hash, exact target list, and fresh target preimage hashes.
- Produces: an order-stable `APPLY` or a lane-local `HOLD`/`REJECT`; only `APPLY` authorizes Tasks 2–7.

- [ ] **Step 1: Load the preflight skill at execution time and use it as the sole review contract**

Do not add another support/falsify/neutral review. Freeze one snapshot containing counts, hashes, paths, sourceSet ownership, target states, and the approved scope without raw source snippets.

- [ ] **Step 2: Run exactly the three logical queries**

`POSITIVE_QUERY` must test whether the declared files and evidence chain can implement the approved producer without changing runtime behavior. `NEGATIVE_QUERY` must attack baseline fidelity, duplicate-FQCN semantic drift, stale inputs, unstable identity, cyclic hashing, partial publication, and scorecard overwrite. `NEUTRAL_QUERY` receives only the frozen snapshot and the two packets, evaluates A–B and B–A ordering, and returns `APPLY`, `HOLD`, or `REJECT`.

- [ ] **Step 3: Require stable `APPLY` and freeze preimages**

Record SHA-256 and existence state for every target immediately before the first edit. New files record `ABSENT`; existing untracked files are still user-owned and receive a real preimage hash. If the verdict changes with packet order or any target changes after the snapshot, rerun the gate once with a new snapshot; otherwise stop.

- [ ] **Step 4: Acquire the isolated-worktree source-edit lease**

```powershell
$isolatedOwnerId = [guid]::NewGuid().ToString('N')
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action begin -Role desktop -Root $isolatedRoot `
  -Topic current-tree-structural-debt-ledger -OwnerId $isolatedOwnerId -TtlMinutes 180
if ($LASTEXITCODE -ne 0) { throw 'source-lease-begin-failed' }
```

Keep the owner ID in process memory and release the lease in `finally`. Never print it as a credential or persist it outside the lease contract.

- [ ] **Step 5: Record the gate result**

Suggested commit message: `chore: approve structural audit producer targets`. Do not stage or commit.

### Task 2: Expose Complete Harmony-Owned Ledger Evidence

**Files:**

- Modify: `scripts/harmony_pressure_report.py`
- Modify: `scripts/test_harmony_pressure_report.py`

**Interfaces:**

- Consumes: active Java files already scanned by `build_report(root: Path)`.
- Produces: existing summary fields unchanged plus `ledgerEvidence.runtimeCrossSubsystemLargeFiles`, `ledgerEvidence.broadCatchWithoutLocalBreadcrumbFiles`, and `ledgerEvidence.manualPromptCandidateFiles`.

- [ ] **Step 1: Add RED tests for complete, bounded, deterministic evidence**

Add tests named:

```text
test_ledger_evidence_includes_every_runtime_large_file_not_only_top_25
test_ledger_evidence_includes_every_broad_catch_file_without_source_snippets
test_ledger_evidence_is_path_sorted_and_numeric_fields_are_nonnegative
test_manual_prompt_evidence_remains_separate_from_deterministic_categories
```

Create at least 27 temporary runtime files to prove the new field is complete while the existing `topRuntimeCrossSubsystemFiles` remains capped at 25. Assert each evidence row's exact field set and assert serialized output contains no source body.

- [ ] **Step 2: Run focused RED**

```powershell
python -X utf8 scripts\test_harmony_pressure_report.py
```

Expected: new assertions fail because `ledgerEvidence` is absent. Existing tests must remain green up to the new contract.

- [ ] **Step 3: Add the structured projection without changing detection logic**

Use the already calculated lists. Do not rescan files and do not introduce new regular expressions:

```python
runtime_large_evidence = sorted(
    (
        {
            "file": row["file"],
            "lines": int(row["lines"]),
            "subsystems": list(row["subsystems"]),
            "hitScore": int(row["hitScore"]),
        }
        for row in runtime_large_cross
    ),
    key=lambda row: (row["file"].casefold(), row["file"]),
)
broad_catch_evidence = sorted(
    (
        {
            "file": row["file"],
            "lines": int(row["lines"]),
            "broadCatchBlocks": int(row["broadCatchBlocks"]),
            "broadCatchWithoutLocalBreadcrumbApprox": int(
                row["broadCatchWithoutLocalBreadcrumbApprox"]
            ),
        }
        for row in catch_pressure_files
        if int(row["broadCatchWithoutLocalBreadcrumbApprox"]) > 0
    ),
    key=lambda row: (row["file"].casefold(), row["file"]),
)
```

Return those lists under:

```python
"ledgerEvidence": {
    "runtimeCrossSubsystemLargeFiles": runtime_large_evidence,
    "broadCatchWithoutLocalBreadcrumbFiles": broad_catch_evidence,
    "manualPromptCandidateFiles": sorted(
        manual_prompt_candidates,
        key=lambda row: (row["file"].casefold(), row["file"]),
    ),
},
```

Keep `generatedAt`, all existing counts, and top-list compatibility fields intact.

- [ ] **Step 4: Run GREEN and a focused mutation check**

```powershell
python -X utf8 scripts\test_harmony_pressure_report.py
```

Expected: pass. Then temporarily reverse one fixture input order through the test only and prove output ordering stays identical.

- [ ] **Step 5: Record task evidence**

Record test count, exit code, and the three evidence-list counts only. Suggested commit message: `feat: expose complete harmony ledger evidence`. Do not stage or commit.

### Task 3: Publish Duplicate-FQCN Evidence From the Existing App Owner

**Files:**

- Modify: `app/build.gradle.kts`
- Modify: `scripts/test_app_build_sourceset_contract.py`

**Interfaces:**

- Consumes: `dupFqcns`, `excludedFqcns`, `hardExcludedFqcns`, `keptFqcns`, `mainByFqcn`, and `cleanByFqcn` already computed by `:app:generateDupFqcnExcludes`.
- Produces: provider-backed `layout.buildDirectory.file("reports/dup-fqcn-evidence.json")` with counts, collision classifications, relative paths, and a semantic hash.

- [ ] **Step 1: Add contract RED**

Extend `AppBuildSourceSetContractTest` with assertions for:

```text
dupFqcnEvidenceFile
reports/dup-fqcn-evidence.json
schemaVersion
duplicateFqcnSourceCollisionCount
duplicateFqcnGeneratedExcludeCount
duplicateFqcnHardExcludeCount
duplicateFqcnPackagedActiveCount
duplicateFqcnActiveCount
packagingState
semanticHash
outputs.file(dupFqcnEvidenceFile)
```

Also assert that the evidence write occurs inside the existing `generateDupFqcnExcludes` body and that no second task scans Java roots.

- [ ] **Step 2: Run RED**

```powershell
python -X utf8 scripts\test_app_build_sourceset_contract.py
```

Expected: fail on the missing evidence provider and fields.

- [ ] **Step 3: Add deterministic JSON helpers and the output provider**

Use JDK classes already available to the Kotlin DSL; add no dependency. Add imports for `java.security.MessageDigest` and `java.time.Instant`, plus compact helpers that JSON-escape control characters and calculate lowercase SHA-256. Define:

```kotlin
val dupFqcnEvidenceFile = layout.buildDirectory.file("reports/dup-fqcn-evidence.json")
```

Declare `outputs.file(dupFqcnEvidenceFile)` on `generateDupFqcnExcludes`.

- [ ] **Step 4: Project the exact existing decision into evidence**

For every sorted `dupFqcns` value, derive paths with `rootProject.projectDir.toPath().relativize(mainByFqcn.getValue(fqcn).toPath())` and `rootProject.projectDir.toPath().relativize(cleanByFqcn.getValue(fqcn).toPath())`, then classify it exactly once:

```kotlin
val packagingState = when {
  excludedSet.contains(fqcn) -> "GENERATED_EXCLUDE"
  hardExcludedFqcns.contains(fqcn) -> "HARD_EXCLUDE"
  else -> "PACKAGED_ACTIVE"
}
```

The JSON schema is `awx.dup-fqcn-evidence.v1`. It contains `generatedAt`, mode/filter/action labels, the four explicit counts, backward-compatible `duplicateFqcnActiveCount` equal to `duplicateFqcnPackagedActiveCount`, sorted collision rows, and `semanticHash`. Each collision row contains only `fqcn`, `rootPath`, `appPath`, `packagingState`, and a SHA-256 evidence fingerprint. Compute `semanticHash` from canonical collision/count material before adding `generatedAt`.

If either active root is missing, write a schema-valid `status="roots-missing"` record and let the downstream audit reject it; do not fabricate a current zero-collision result.

- [ ] **Step 5: Run contract GREEN and live app evidence generation**

```powershell
python -X utf8 scripts\test_app_build_sourceset_contract.py
.\gradlew.bat :app:generateDupFqcnExcludes --no-daemon --rerun-tasks
```

Expected: test passes; Gradle reports the existing duplicate count; the JSON count equation holds:

```text
sourceCollisionCount = generatedExcludeCount + hardExcludeCount + packagedActiveCount
duplicateFqcnActiveCount = packagedActiveCount
```

Do not assert the planning-time numeric count as a permanent test literal.

- [ ] **Step 6: Record task evidence**

Record only counts, status, and semantic hash. Suggested commit message: `feat: publish app duplicate fqcn evidence`. Do not stage or commit.

### Task 4: Specify the Quantitative Producer With Focused RED Tests

**Files:**

- Create: `scripts/test_dynamic_rag_quant_audit.py`
- Test target not yet present: `scripts/dynamic_rag_quant_audit.py`

**Interfaces:**

- Consumes: temporary roots, synthetic NUL-delimited Git files, fresh harmony/test-tree/duplicate JSON, explicit active roots, candidate cap, and an injected UTC clock.
- Produces expectations for `build_audit(inputs: AuditInputs, generated_at: datetime) -> AuditBundle` and `publish_bundle(bundle: AuditBundle, outputs: AuditOutputs) -> None`.

- [ ] **Step 1: Build only temporary-repository fixtures**

Fixtures create real Java files, raw Git capture bytes, and JSON artifacts below `TemporaryDirectory`. They never import or scan the canonical checkout. Use helper names:

```python
def write_java(root: Path, relative: str, line_count: int, body_tail: str = "") -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    filler_count = max(0, line_count - 3)
    text = "package fixture;\n" + ("// filler\n" * filler_count) + body_tail + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")
    return path

def canonical_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
```

- [ ] **Step 2: Add path, root, Git-state, and baseline RED cases**

Add tests named:

```text
test_missing_active_root_fails_with_active-root-missing
test_inactive_java_root_is_never_scanned
test_absolute_traversal_uri_and_casefold_duplicate_paths_fail
test_reparse_escape_fails_closed
test_porcelain_v2_z_parses_clean_modified_deleted_untracked_and_rename
test_baseline_hash_is_stable_across_input_enumeration_order
test_generated_artifact_policy_prevents_second_run_baseline_drift
```

- [ ] **Step 3: Add category, identity, grouping, and count RED cases**

Add tests named:

```text
test_file_size_threshold_is_strictly_greater_than_2000
test_cross_subsystem_threshold_is_strictly_greater_than_1000
test_broad_catch_rows_use_harmony_evidence_without_rescanning
test_duplicate_source_and_packaged_active_semantics_are_distinct
test_size_and_cross_subsystem_manifestations_share_one_owner_group
test_multiple_duplicate_manifestations_share_one_fqcn_owner_group
test_only_eligible_open_groups_count_toward_repair_selection
test_review_required_rows_do_not_count_as_verified_or_eligible_closures
```

- [ ] **Step 4: Add determinism, cap, safety, and transaction RED cases**

Add tests named:

```text
test_repeated_builds_keep_ids_order_payload_hashes_and_audit_run_id
test_generated_at_changes_only_nondeterministic_envelope_fields
test_duplicate_issue_id_semantic_key_or_group_owner_fails_closed
test_cap_1100_counts_full_universe_and_reports_overflow
test_fewer_than_900_eligible_groups_reports_honest_target_gap
test_stale_malformed_or_incoherent_required_input_is_rejected
test_artifacts_contain_no_absolute_path_url_snippet_prompt_response_or_secret
test_cross_artifact_links_recompute_from_payloads
test_replace_failure_restores_every_prior_output
test_metrics_is_the_last_replaced_commit_record
```

- [ ] **Step 5: Run RED**

```powershell
python -X utf8 scripts\test_dynamic_rag_quant_audit.py
```

Expected: import/module failure for `dynamic_rag_quant_audit`. The test file itself must compile with `python -m py_compile`.

- [ ] **Step 6: Record RED evidence**

Record command, exit code, and first expected import failure. Suggested commit message: `test: define structural quant audit contract`. Do not stage or commit.

### Task 5: Implement the Deterministic Shell-Free Producer

**Files:**

- Create: `scripts/dynamic_rag_quant_audit.py`
- Modify as tests clarify: `scripts/test_dynamic_rag_quant_audit.py`

**Interfaces:**

- Public constants: `SCHEMA_VERSION`, `DEFAULT_CANDIDATE_CAP`, `TARGET_FLOOR`, `TARGET_CEILING`, active-root defaults, allowed enum sets, reason-code allowlist, and generated-artifact policy version.
- Public types: frozen `AuditInputs`, `AuditOutputs`, and `AuditBundle` dataclasses; `AuditContractError` with a count-safe `reason_code`.
- Public functions: `parse_porcelain_v2_z`, `build_workspace_manifest`, `load_required_input`, `build_ledger_rows`, `build_audit`, `validate_bundle`, `publish_bundle`, and `main`.

- [ ] **Step 1: Add exact schemas, enums, thresholds, and failure codes**

Use these constants:

```python
SCHEMA_VERSION = "awx.structural-design-audit.v1"
METRICS_SCHEMA = "awx.dynamic-rag-quant-audit-metrics.v1"
BASELINE_SCHEMA = "awx.structural-design-baseline.v1"
LEDGER_SCHEMA = "awx.structural-design-debt-ledger-row.v1"
DEFAULT_CANDIDATE_CAP = 1100
TARGET_FLOOR = 900
TARGET_CEILING = 1100
FILE_SIZE_THRESHOLD = 2000
CROSS_SUBSYSTEM_THRESHOLD = 1000
ACTIVE_JAVA_ROOTS = ("main/java", "app/src/main/java_clean")
STATUSES = frozenset({"OPEN", "HOLD", "REJECTED_FALSE_POSITIVE", "SUPERSEDED", "PATCHED_UNVERIFIED", "VERIFIED_CLOSED"})
ELIGIBILITIES = frozenset({"ELIGIBLE", "REVIEW_REQUIRED", "EXTERNAL_EVIDENCE_REQUIRED", "REJECTED_BY_CONTRACT"})
SEVERITY_RANK = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3}
ELIGIBILITY_RANK = {"ELIGIBLE": 0, "REVIEW_REQUIRED": 1, "EXTERNAL_EVIDENCE_REQUIRED": 2, "REJECTED_BY_CONTRACT": 3}
```

Stable CLI failure codes include `active-root-missing`, `path-invalid`, `path-case-collision`, `reparse-path-risk`, `git-capture-malformed`, `required-input-missing`, `required-input-malformed`, `required-input-stale`, `duplicate-count-inconsistent`, `issue-identity-conflict`, `group-owner-conflict`, `public-artifact-secret-hit`, `artifact-link-mismatch`, `output-replace-failed`, and `semantic-nondeterminism`.

- [ ] **Step 2: Parse declared Git captures without launching Git**

`parse_porcelain_v2_z(raw: bytes)` supports record types `1`, `2`, `u`, and `?`; it consumes the extra original-path token for type `2`, rejects `!`, malformed field counts, invalid UTF-8, and duplicate case-folded paths. `build_workspace_manifest` combines the capture with `git ls-files --cached --others --exclude-standard -z`, hashes current bytes, represents deleted rows explicitly, and applies only `structural-audit-generated.v1` exclusions.

Never read `.git`, infer branch names, or run a subprocess. Read `HEAD` and branch only from their explicit capture files.

Use this exact generated-artifact policy in both Task 0's transfer manifest and the producer:

```python
GENERATED_ARTIFACT_POLICY_VERSION = "structural-audit-generated.v1"
GENERATED_PATH_PREFIXES = (
    "build/",
    "app/build/",
    "var/codex-smoke/",
    "logs/",
)
GENERATED_TOP_LEVEL_PREFIXES = (".gradle",)
GENERATED_TOP_LEVEL_NAMES = frozenset({".playwright-cli"})
GENERATED_VAR_NAME_RULES = ("starts-with:gradle", "contains:project-cache")
GENERATED_VERIFICATION_PATHS = frozenset(
    {
        "verification/dynamic-rag-quant-audit-metrics.json",
        "verification/structural-design-baseline.json",
        "verification/structural-design-debt-ledger.jsonl",
        "verification/dynamic-rag-harmony-pressure-metrics.json",
        "verification/test-tree-contamination-metrics.json",
        "verification/source-health-scorecard.json",
        "verification/source-health-failure-pattern-events.ndjson",
        "verification/source-health-patchdrop-manifest-contract.json",
    }
)
```

Exclude sibling temporary and rollback names derived from those exact verification paths. At the repository top level, exclude a path when its first segment starts with `.gradle` or equals `.playwright-cli`. Under `var/`, exclude a path when its second segment starts with `gradle` or contains `project-cache`; `var/codex-smoke/` remains an explicit generated prefix. These rules cover live non-ignored cache directory variants without broadening the policy to arbitrary source, docs, data, or evidence directories.

- [ ] **Step 3: Validate roots, paths, and prerequisite freshness**

Resolve each active root below the declared root, reject symlink/junction/reparse traversal, and map it to `rootMain` or `appMainClean`. Required harmony and test-tree inputs use the scorecard's 24-hour freshness semantics, including local interpretation for a legacy timezone-naive timestamp. Duplicate evidence must have `status` absent or `current`, valid schema, a recomputable semantic hash, sorted unique FQCNs, and the exact count equation.

Build the baseline payload with this exact public field set before envelope fields are added:

```python
baseline_payload = {
    "schemaVersion": BASELINE_SCHEMA,
    "canonicalRootHash": canonical_root_hash,
    "canonicalRootPathLength": len(str(root.resolve())),
    "head": captured_head,
    "branch": captured_branch,
    "activeSourceSets": ["rootMain", "appMainClean"],
    "activeSourceSetConfigurationHash": active_source_set_configuration_hash,
    "trackedPathCount": tracked_path_count,
    "modifiedPathCount": modified_path_count,
    "deletedPathCount": deleted_path_count,
    "untrackedPathCount": untracked_path_count,
    "activeJavaFileCount": active_java_file_count,
    "activeJavaBytesTotal": active_java_bytes_total,
    "pathStateContentRows": ordered_manifest_rows,
    "baselineId": baseline_id,
    "secretPatternHitCount": secret_pattern_hit_count,
    "ignoredArtifactPolicyVersion": GENERATED_ARTIFACT_POLICY_VERSION,
}
```

Each manifest row has exactly `path`, `gitState`, `sizeBytes`, and `contentSha256`. Compute `canonicalRootHash` from the case-folded resolved root with `/` separators, but never publish the raw resolved root. Compute the sourceSet configuration hash from the ordered mapping `rootMain=main/java` and `appMainClean=app/src/main/java_clean`. Compute `baselineId` from schema, captured `HEAD`, branch, sourceSet configuration hash, ignored-policy version, and canonical bytes of the ordered manifest rows.

Use the validated harmony `secretPatternHits` count for the count-only baseline field; do not store matches. Validate that the count is a nonnegative integer.

- [ ] **Step 4: Build deterministic issue rows**

Emit only the five approved v1 categories. Eligibility and owner grouping are exact:

| Category | Eligibility / initial status | Root-cause owner key | Severity |
|---|---|---|---|
| `DUPLICATE_FQCN_PACKAGED_ACTIVE` | `ELIGIBLE` / `OPEN` | literal `fqcn-owner:` concatenated with the FQCN | `CRITICAL` |
| `BROAD_CATCH_NO_BREADCRUMB` | `ELIGIBLE` / `OPEN` | literal `fail-soft-owner:` concatenated with the canonical path | `HIGH` when count is at least 3, otherwise `MEDIUM` |
| `DUPLICATE_FQCN_SOURCE_COLLISION` | `REVIEW_REQUIRED` / `HOLD` | literal `fqcn-owner:` concatenated with the FQCN | `HIGH` |
| `CROSS_SUBSYSTEM_CONCENTRATION` | `REVIEW_REQUIRED` / `HOLD` | literal `structural-owner:` concatenated with canonical path and symbol | `HIGH` above 2,000 lines or at least four subsystems, otherwise `MEDIUM` |
| `FILE_SIZE_CONCENTRATION` | `REVIEW_REQUIRED` / `HOLD` | literal `structural-owner:` concatenated with canonical path and symbol | `HIGH` above 4,000 lines, otherwise `MEDIUM` |

Do not emit manual-prompt candidates in v1. Preserve them only in harmony evidence for a later reviewed extension.

For file-owned categories, set `symbol` to the Java filename stem and set `activeSourceSet` to `rootMain` or `appMainClean` from the owning active root. Use only these evidence reasons: `active-java-lines-over-2000`, `runtime-cross-subsystem-lines-over-1000`, `broad-catch-without-local-breadcrumb`, `duplicate-source-fqcn`, and `duplicate-packaged-active-fqcn`. Numeric evidence is restricted to nonnegative `lineCount`, `subsystemCount`, `subsystemHitScore`, `broadCatchCount`, and duplicate classification counts. Compute `evidenceFingerprint` from category, canonical path, symbol, reason, and canonical numeric evidence; never hash or store source text.

Read Java as strict UTF-8 (accepting an initial UTF-8 BOM), count lines with `splitlines()`, and fail with `source-decode-failed` on undecodable input. Do not use error-ignoring decoding because it can make identities platform-dependent.

Every ledger core row has exactly these fields before envelope fields are attached:

```python
ledger_core_row = {
    "schemaVersion": LEDGER_SCHEMA,
    "issueId": issue_id,
    "rootCauseGroupId": root_cause_group_id,
    "baselineId": baseline_id,
    "category": category,
    "severity": severity,
    "activeSourceSet": active_source_set,
    "path": canonical_relative_path,
    "symbol": symbol,
    "evidenceReason": evidence_reason,
    "evidenceFingerprint": evidence_fingerprint,
    "numericEvidence": numeric_evidence,
    "suspectedBoundary": suspected_boundary,
    "fixEligibility": fix_eligibility,
    "proofCommand": proof_command,
    "status": initial_status,
    "supersedes": [],
}
```

The final JSONL row adds only `generatedAt`, `auditRunId`, and `artifactLinks`. Reject unknown fields so raw snippets or accidental diagnostic payloads cannot drift into the public contract.

Derive `issueId` from the full lowercase SHA-256 of:

```text
schemaVersion | category | canonicalRelativePath | symbol | normalizedEvidenceKey
```

Derive `rootCauseGroupId` from `schemaVersion | suspectedBoundary | ownerKey`. File-size and cross-subsystem rows for the same Java owner therefore share one closure credit; duplicate source and packaged-active rows for one FQCN do the same.

Allowed proof commands are exact strings, not source-derived commands:

```text
python -X utf8 scripts\test_dynamic_rag_quant_audit.py
python -X utf8 scripts\test_harmony_pressure_report.py
gradlew.bat :app:generateDupFqcnExcludes
gradlew.bat dynamicRagQuantAudit
```

- [ ] **Step 5: Sort, cap, and count root-cause groups honestly**

Sort by severity rank, eligibility rank, category, case-folded path, original path, symbol, and full issue hash. Validate the full universe before truncation. Metrics must contain row and distinct-group counts, full per-category totals, emitted totals, overflow, `truncated`, `eligibleRows`, `eligibleRootCauseGroups`, `reviewOnlyRows`, `verifiedClosedRootCauseGroups=0`, target band, and `targetGap=max(0, 900-eligibleRootCauseGroups)`.

No status is initialized to `VERIFIED_CLOSED`. Detection never earns closure credit.

Build the metrics payload with these required fields:

```python
metrics_payload = {
    "schemaVersion": METRICS_SCHEMA,
    "baselineId": baseline_id,
    "activeJavaFileCount": active_java_file_count,
    "activeJavaLocTotal": sum(active_java_line_counts),
    "activeJavaLocP95": active_java_loc_p95,
    "largeActiveFilesOver2000": large_active_files_over_2000,
    "duplicateFqcnSourceCollisionCount": duplicate_source_count,
    "duplicateFqcnGeneratedExcludeCount": duplicate_generated_count,
    "duplicateFqcnHardExcludeCount": duplicate_hard_count,
    "duplicateFqcnPackagedActiveCount": duplicate_packaged_count,
    "duplicateFqcnActiveCount": duplicate_packaged_count,
    "secretPatternHitCount": secret_pattern_hit_count,
    "harmonyPressureSummary": harmony_pressure_summary,
    "testTreeContamination": test_tree_summary,
    "ledgerSummary": ledger_summary,
    "runtimeProviderDisabledSmoke": provider_evidence_needed,
    "supabaseReadonlySmoke": supabase_evidence_needed,
}
```

Calculate p95 with the nearest-rank rule: sort line counts ascending and select index `max(0, ceil(0.95 * n) - 1)`, returning `0.0` for an empty set and a finite float otherwise. `largeActiveFilesOver2000` counts strict `lineCount > 2000`. `harmonyPressureSummary` copies only the existing numeric summary fields consumed by the scorecard; `testTreeContamination` copies only `riskScore`, `missingImportCount`, and `affectedTestFileCount`. Neither summary may carry raw source snippets.

- [ ] **Step 6: Build linked payload hashes without circular hashing**

Canonical payload bytes use UTF-8, sorted object keys, compact separators, LF, and no floating non-finite values. The metrics payload used below does not yet contain `semanticArtifactHash`. Compute:

```python
baseline_payload_hash = sha256_hex(canonical_json_bytes(baseline_payload))
ledger_payload_hash = sha256_hex(canonical_ndjson_bytes(ledger_core_rows))
metrics_payload_hash = sha256_hex(canonical_json_bytes(metrics_payload))
audit_run_id = sha256_hex(
    f"{SCHEMA_VERSION}|{baseline_id}|{baseline_payload_hash}|{ledger_payload_hash}|{metrics_payload_hash}".encode("utf-8")
)
artifact_links = {
    "baselinePayloadSha256": baseline_payload_hash,
    "ledgerPayloadSha256": ledger_payload_hash,
    "metricsPayloadSha256": metrics_payload_hash,
}
```

Add `generatedAt`, `auditRunId`, and the same `artifactLinks` to the baseline and metrics envelopes and to every ledger row. Set metrics `semanticArtifactHash` to `metricsPayloadSha256`. `validate_bundle` strips only those envelope fields and recomputes every link.

- [ ] **Step 7: Keep external evidence explicit and non-fabricated**

Metrics contains:

```python
"runtimeProviderDisabledSmoke": {
    "status": "evidence_needed",
    "reason": "optional-provider-evidence-not-declared",
},
"supabaseReadonlySmoke": {
    "status": "evidence_needed",
    "reason": "project-scoped-readonly-evidence-not-declared",
    "readOnlyMode": True,
    "mutationAllowed": False,
},
```

Do not call either system and do not insert HTTP status, project ref, provider attempt, or success booleans.

- [ ] **Step 8: Implement transactional publication**

Write each artifact to a closed sibling temporary file, flush and `fsync`, re-read and validate all three, then create at most one sibling `.rollback` copy for each existing destination. Replace baseline first, ledger second, and metrics last. On any exception, restore all prior destinations or remove newly created destinations, then raise `output-replace-failed`. Never leave a new metrics commit record pointing at a partial bundle.

The CLI returns `2` with one redacted line containing `reason=` followed by an allowlisted reason code and count fields only for contract failures. It never prints raw source, absolute paths, URLs, exception bodies, or environment values.

- [ ] **Step 9: Implement the CLI contract**

Required/explicit arguments are:

```text
--root
--active-java-root (repeatable; Gradle passes both expected roots)
--git-head-input
--git-branch-input
--git-paths-input
--git-status-input
--harmony-input
--test-tree-input
--dup-fqcn-input
--metrics-output
--baseline-output
--ledger-output
--candidate-cap
```

Production obtains current UTC time internally. Tests inject a fixed aware `datetime` into `build_audit`; there is no production CLI switch that can forge freshness.

- [ ] **Step 10: Run GREEN, determinism, and mutation checks**

```powershell
python -m py_compile scripts\dynamic_rag_quant_audit.py scripts\test_dynamic_rag_quant_audit.py
python -X utf8 scripts\test_dynamic_rag_quant_audit.py
```

Expected: every named test passes. Mutate one test fixture count equation and one expected sort key in memory/test setup, prove the specific tests fail, restore, and rerun GREEN.

- [ ] **Step 11: Record task evidence**

Record test count, full-universe row/group counts from fixtures, and zero public-artifact secret hits. Suggested commit message: `feat: add deterministic structural quant audit producer`. Do not stage or commit.

### Task 6: Make the Scorecard Fail Closed Before Writing

**Files:**

- Modify: `scripts/source_health_scorecard.py`
- Modify: `scripts/test_source_health_scorecard.py`

**Interfaces:**

- Consumes: the canonical metrics, baseline, and ledger bundle.
- Produces: scorecard output only when quant metrics are current and the linked bundle is coherent; otherwise exit `2`, reason `quant-metrics-missing-or-stale`, and preserve all prior scorecard/sidecar bytes.

- [ ] **Step 1: Add fail-closed RED tests**

Add tests named:

```text
test_main_missing_quant_returns_2_and_preserves_prior_outputs
test_main_stale_quant_returns_2_and_preserves_prior_outputs
test_main_malformed_quant_returns_2_and_preserves_prior_outputs
test_main_cross_artifact_mismatch_returns_2_and_preserves_prior_outputs
test_main_current_linked_quant_bundle_writes_scorecard
test_green_fixture_uses_packaged_active_duplicate_semantics
```

Each preservation test prewrites unique sentinel bytes to the scorecard and both evidence sidecars, calls `main`, and compares exact post-call bytes. Capture stderr and assert only the stable reason plus freshness status is present.

Update `_write_green_source_runtime_fixture` once to emit a minimal valid linked metrics/baseline/ledger bundle; do not duplicate bundle hashing in every test.

- [ ] **Step 2: Run RED**

```powershell
python -X utf8 scripts\test_source_health_scorecard.py
```

Expected: new direct-CLI tests fail because current `main` writes even when quant input is absent or stale.

- [ ] **Step 3: Add the quant bundle preflight**

Add canonical paths for the baseline and ledger. Implement `validate_current_quant_bundle(root: Path) -> dict[str, Any]` to reuse `_canonical_json_input` freshness, require current metrics, validate schema/run/link fields, stream-parse nonblank JSONL rows, recompute all three payload hashes, and require one `auditRunId`.

Keep `build_scorecard(root)` diagnostic-friendly for existing library tests: it may still expose missing/stale input in `inputFreshness`. The command-line `main` must call `validate_current_quant_bundle` before `build_scorecard` and before opening any destination.

```python
try:
    validate_current_quant_bundle(root.resolve())
except QuantMetricsContractError as exc:
    print(
        f"[AWX][source-health] reason=quant-metrics-missing-or-stale status={exc.status}",
        file=sys.stderr,
    )
    return 2
```

`QuantMetricsContractError.status` is allowlisted (`missing`, `malformed`, `stale`, `future_generated_at`, `cross-artifact-mismatch`, `schema-invalid`); it never contains paths or raw values.

- [ ] **Step 4: Keep duplicate semantics backward-compatible**

Continue reading `duplicateFqcnActiveCount` for source integrity. The producer guarantees that field equals `duplicateFqcnPackagedActiveCount`; raw source collisions remain a separate structural metric and do not incorrectly zero package integrity.

- [ ] **Step 5: Run GREEN and legacy regression tests**

```powershell
python -m py_compile scripts\source_health_scorecard.py scripts\test_source_health_scorecard.py
python -X utf8 scripts\test_source_health_scorecard.py
```

Expected: all prior scorecard tests plus the six new tests pass.

- [ ] **Step 6: Record task evidence**

Record test count and the four fail-closed status codes, not sentinel contents. Suggested commit message: `fix: require current linked quant metrics before scorecard writes`. Do not stage or commit.

### Task 7: Wire the Gradle Evidence Chain and Git Capture

**Files:**

- Modify: `build.gradle.kts`
- Modify: `scripts/test_source_health_scorecard.py`
- Consume provider from: `app/build.gradle.kts`

**Interfaces:**

- Produces host-split raw Git capture files under `layout.buildDirectory.dir("generated/structural-audit/git")` and the `dynamicRagQuantAudit` task.
- Changes `sourceHealthScorecard` from optional quant input to `dependsOn("dynamicRagQuantAudit")` with required metrics/baseline/ledger inputs.

- [ ] **Step 1: Add Gradle contract RED**

Extend `test_gradle_task_is_registered_for_repo_owned_refresh` or add a nearby test that asserts:

```text
captureStructuralAuditGitState
git status --porcelain=v2 -z --untracked-files=all
git ls-files --cached --others --exclude-standard -z
dynamicRagQuantAudit
:app:generateDupFqcnExcludes
scripts/dynamic_rag_quant_audit.py
--dup-fqcn-input
verification/structural-design-baseline.json
verification/structural-design-debt-ledger.jsonl
dependsOn("dynamicRagQuantAudit")
```

Assert the old conditional optional quant-input block is absent after GREEN.

- [ ] **Step 2: Run the Gradle contract RED**

```powershell
python -X utf8 scripts\test_source_health_scorecard.py
```

Expected: only the new Gradle-chain assertions fail.

- [ ] **Step 3: Capture Git evidence before generated verification artifacts**

Register `captureStructuralAuditGitState` as an always-refreshing verification task. Use `providers.exec` four times and write raw bytes to regular files for `rev-parse HEAD`, `branch --show-current`, `ls-files --cached --others --exclude-standard -z`, and `status --porcelain=v2 -z --untracked-files=all`. The task is the only layer that invokes Git for the producer.

Core capture pattern:

```kotlin
fun captureGitBytes(vararg arguments: String): ByteArray =
    providers.exec {
        workingDir(layout.projectDirectory)
        commandLine("git", *arguments)
    }.standardOutput.asBytes.get()
```

Create parents, use `writeBytes`, declare all four outputs, and set `outputs.upToDateWhen { false }`. Do not log captured path bytes.

Make `harmonyPressureReport`, `testTreeContaminationReport`, and `:app:generateDupFqcnExcludes` depend on capture so the approved baseline is observed before their outputs appear. Set `outputs.upToDateWhen { false }` on those three evidence tasks: source inputs can remain unchanged for more than 24 hours while their `generatedAt` values become stale.

- [ ] **Step 4: Register `dynamicRagQuantAudit`**

The task depends on capture, harmony, test-tree, and `:app:generateDupFqcnExcludes`. Declare active roots, the producer script, all four Git captures, and all three prerequisite JSON files as inputs. Declare the metrics, baseline, and ledger as outputs, and set `outputs.upToDateWhen { false }` so a normal Gradle scorecard request always receives a current audit rather than an artifact that aged past the freshness window. Pass the app evidence through:

```kotlin
val appDupFqcnEvidence = project(":app").layout.buildDirectory.file("reports/dup-fqcn-evidence.json")
```

The command uses `python scripts/dynamic_rag_quant_audit.py`, repeats `--active-java-root` for `main/java` and `app/src/main/java_clean`, passes every capture/input/output explicitly, and sets `--candidate-cap 1100`.

- [ ] **Step 5: Make the scorecard depend on the quant producer**

Replace `dependsOn("harmonyPressureReport", "testTreeContaminationReport")` with `dependsOn("dynamicRagQuantAudit")`. Declare the three linked audit artifacts as non-optional inputs. Keep optional websoak, DB-gap, and `var/codex-smoke` evidence handling unchanged.

- [ ] **Step 6: Run Kotlin DSL and task-graph GREEN**

```powershell
python -X utf8 scripts\test_source_health_scorecard.py
.\gradlew.bat help --no-daemon
.\gradlew.bat dynamicRagQuantAudit sourceHealthScorecard --dry-run --no-daemon
```

Expected: Python contracts pass; Kotlin DSL compiles; dry-run ordering shows capture before harmony/test-tree/app evidence, those inputs before `dynamicRagQuantAudit`, and the audit before `sourceHealthScorecard`.

- [ ] **Step 7: Record task evidence**

Record task order and exit codes only; never print raw Git capture files. Suggested commit message: `build: wire structural quant audit evidence chain`. Do not stage or commit.

### Task 8: Verify the Isolated Implementation and Determinism

**Files:**

- Verify all nine declared targets.
- Generate the three audit artifacts and scorecard outputs.
- Do not touch application Java/resources.

**Interfaces:**

- Consumes: completed Tasks 2–7 under unchanged target preimages and active lease.
- Produces: focused GREEN, real current-worktree audit artifacts, two-run semantic determinism proof, and an honest candidate/target-gap summary.

- [ ] **Step 1: Configure isolated Gradle caches and prove Java 17**

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-structural-ledger-isolated'
$env:GRADLE_USER_HOME = Join-Path $isolatedRoot 'var\gradle-user-home\desktop-structural-ledger-isolated'
$projectCache = Join-Path $isolatedRoot 'var\gradle-project-cache\desktop-structural-ledger-isolated'
java -version
```

Expected: Java major version 17. Do not reuse another active worker's cache or boot ports.

- [ ] **Step 2: Run focused Python contracts in the required order**

```powershell
python -X utf8 scripts\test_dynamic_rag_quant_audit.py
python -X utf8 scripts\test_harmony_pressure_report.py
python -X utf8 scripts\test_tree_contamination_report.py
python -X utf8 scripts\test_source_health_scorecard.py
python -X utf8 scripts\test_app_build_sourceset_contract.py
```

Expected: all pass. Stop at the first failure and preserve its first error; do not broaden verification while RED.

- [ ] **Step 3: Generate the real linked bundle and scorecard**

```powershell
.\gradlew.bat dynamicRagQuantAudit sourceHealthScorecard `
  --no-daemon --project-cache-dir $projectCache --rerun-tasks
```

Expected: metrics, baseline, and ledger validate as one run; scorecard reports current quant input. Record only schema versions, hashes, counts, `eligibleRootCauseGroups`, `verifiedClosedRootCauseGroups`, and `targetGap`.

- [ ] **Step 4: Prove unchanged-input semantic determinism**

Read and retain only the first run's `baselineId`, semantic artifact hash, app duplicate semantic hash, ordered issue IDs, ordered group IDs, per-category counts, and ledger payload hash. Rerun the exact Gradle command, then compare those fields byte-for-byte. `generatedAt` may differ; every retained deterministic field must match.

Expected: match. A mismatch is `semantic-nondeterminism` and blocks integration.

- [ ] **Step 5: Run repository boundary gates**

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test `
  --no-daemon --project-cache-dir $projectCache
git diff --check
```

Expected: Gradle gates pass. `git diff --check` must introduce zero new errors; every declared target must be clean even if an unrelated pre-existing baseline error was recorded in Task 0.

- [ ] **Step 6: Run count-only secret and scope checks**

Scan only the nine declared changed targets plus the three generated public artifacts for the repository high-confidence token families. Print only `changedFileSecretPatternHits=` and `publicArtifactSecretPatternHits=` followed by decimal counts; both counts must be zero. Compare changed paths to the declared target set and reject any undeclared source/resource/build/script change.

- [ ] **Step 7: Release the isolated lease in `finally`**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action end -Role desktop -Root $isolatedRoot `
  -Topic current-tree-structural-debt-ledger -OwnerId $isolatedOwnerId
```

Suggested commit message: `feat: establish current-tree structural debt ledger`. Do not stage or commit.

### Task 9: Reconcile the Verified Hunks Into the Desktop Canonical Root

**Files:**

- Apply only the verified implementation delta for the nine declared targets.
- Do not copy generated build/cache artifacts or overwrite unrelated canonical hunks.

**Interfaces:**

- Consumes: isolated target preimages/postimages, canonical target preimages, verified command evidence, and an order-stable preflight verdict.
- Produces: identical target postimage hashes in the canonical Desktop root, with unrelated canonical paths unchanged.

- [ ] **Step 1: Re-run canonical blockers and target compare-and-swap checks**

From the canonical root, rerun worktree list, branch, `git status --short`, index-lock check, `janitor_inventory.ps1`, and exact target SHA-256/existence checks. Require every canonical target to still match the Task 1 preimage. A mismatch is `canonical-preimage-changed`; do not overwrite or auto-merge it.

- [ ] **Step 2: Acquire a fresh canonical Desktop source-edit lease**

Use `source_edit_session.ps1 -Action begin -Role desktop` with topic `current-tree-structural-debt-ledger-integrate`, a new in-memory owner ID, the canonical root, and a bounded TTL. Recheck all target preimages immediately after acquisition.

- [ ] **Step 3: Replay only the isolated implementation hunks**

The parent Codex executor owns integration. Use `apply_patch` against the canonical files, preserving pre-existing dirty content and line endings. For new files require the canonical preimage to remain `ABSENT`. Do not copy whole modified files over existing canonical files.

- [ ] **Step 4: Prove postimage and scope equality**

Require each canonical target SHA-256 to equal the isolated verified postimage. Compare `git status --short` before and after integration and allow state changes only for the nine declared targets and generated evidence paths. Keep all other status entries byte-identical.

- [ ] **Step 5: Release the canonical lease in `finally`**

Release with the same owner ID even if integration fails. If rollback is required, restore only applied task hunks using captured preimages; never use `git reset`, `git checkout`, or broad file replacement.

- [ ] **Step 6: Record integration evidence**

Record declared changed-path count, matching postimage count, zero undeclared changes, and lease release. Suggested commit message: `feat: integrate structural debt ledger producer`. Do not stage or commit.

### Task 10: Run Final Desktop Proof and Report the Honest Count

**Files:**

- Verify the canonical Desktop targets and regenerated artifacts.
- Read final `verification/dynamic-rag-quant-audit-metrics.json` and ledger counts.

**Interfaces:**

- Consumes: canonical integrated postimages.
- Produces: final Desktop proof for this subproject and the next subproject's deterministic eligible-group intake.

- [ ] **Step 1: Use fresh canonical host-specific caches**

```powershell
$canonicalRoot = (Resolve-Path -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src').ProviderPath
Set-Location -LiteralPath $canonicalRoot
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-structural-ledger-final'
$env:GRADLE_USER_HOME = Join-Path $canonicalRoot 'var\gradle-user-home\desktop-structural-ledger-final'
$finalProjectCache = Join-Path $canonicalRoot 'var\gradle-project-cache\desktop-structural-ledger-final'
```

- [ ] **Step 2: Repeat focused tests and real generation**

```powershell
python -X utf8 scripts\test_dynamic_rag_quant_audit.py
python -X utf8 scripts\test_harmony_pressure_report.py
python -X utf8 scripts\test_tree_contamination_report.py
python -X utf8 scripts\test_source_health_scorecard.py
python -X utf8 scripts\test_app_build_sourceset_contract.py
.\gradlew.bat dynamicRagQuantAudit sourceHealthScorecard `
  --no-daemon --project-cache-dir $finalProjectCache --rerun-tasks
```

- [ ] **Step 3: Repeat deterministic rerun and repository gates**

Repeat the Task 8 semantic comparison, then run:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test `
  --no-daemon --project-cache-dir $finalProjectCache
git diff --check
```

Expected: focused contracts, linked artifacts, task chain, sourceSet hygiene, dependency purity, root compile, and `:app:classes` pass from the canonical Desktop root.

- [ ] **Step 4: Inspect final diff and count-only safety evidence**

Review `git diff --` for existing tracked targets and direct contents for new untracked targets. Confirm no runtime Java, resources, prompt, provider, database, Browser, Computer, dependency version, or unrelated path changed. Repeat the changed-file/public-artifact count-only secret scan and require zero.

- [ ] **Step 5: Report without inflating completion**

The completion report must state:

- exact created/modified target paths;
- focused test commands and pass counts;
- Gradle commands and exit results;
- `baselineId`, payload hashes, semantic determinism result, and candidate cap;
- total/emitted rows, distinct groups, eligible groups, review-required rows, overflow, and `targetGap`;
- `verifiedClosedRootCauseGroups=0` for this measuring subproject unless a separate later source-repair cycle has independently met the closure contract;
- Browser/Computer/provider/Supabase/model/runtime-answer proof as `not_run` or `not_observed`, never inferred;
- one next action: select the highest-priority `ELIGIBLE` groups and create the next bounded repair-cycle plan.

- [ ] **Step 6: Keep the parent goal active**

Do not mark the approximately-1,000-repair goal complete when this plan completes. This subproject is complete only when its nine criteria in the approved spec pass; the next subproject continues until 900–1,100 distinct root-cause groups are independently `VERIFIED_CLOSED`, or fresh evidence proves the range cannot be reached without manufactured or duplicate defects.

Suggested commit message: `feat: add deterministic current-tree structural debt ledger`. Do not stage or commit without new user authority.

## Plan Completion Gate

Before claiming this implementation plan complete, verify all of the following:

- The isolated pre-edit manifest exactly matched the canonical dirty baseline.
- The single three-way preflight was order-stable `APPLY` and all target preimages remained stable.
- Harmony and app evidence came from their existing owners; the producer did not duplicate either detection algorithm.
- The producer invoked no shell or external system.
- Required inputs were current, schemas/count equations were valid, and linked payload hashes recomputed.
- Two unchanged-input runs preserved issue IDs, group IDs, order, counts, baseline ID, audit run ID, and semantic hashes.
- Metrics was published last and publication-failure tests restored all prior bytes.
- Direct scorecard execution failed before writing when quant evidence was missing, stale, malformed, or incoherent.
- Focused tests, LangChain4j purity, sourceSet hygiene, `compileJava`, and `:app:classes` passed in both isolated and final Desktop roots.
- Changed-file and public-artifact high-confidence secret counts were zero.
- No Browser/Computer/provider/Supabase/runtime success was claimed.
- No stage, commit, push, deploy, database mutation, cleanup, or unrelated overwrite occurred.
- The final report used distinct verified root-cause groups as the only repair count and kept the larger goal active.
