# Reviewed Duplicate-Ownership Wave-3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admit exactly one freshly reproduced `RetrievalOrderService` duplicate-ownership predecessor into an empty, hash-pinned wave 3 without modifying Java, changing the category-wide ledger contract, touching `RagControlRuntimeAdapter`, or awarding repair credit.

**Architecture:** `scripts/dynamic_rag_quant_audit.py` remains the sole registry, intake, progress, event, history, overlay, and public-bundle truth owner. It gains strict per-wave v1/v2 dispatch plus dormant repair-target-admission support; waves 1 and 2 continue down the exact v1 branch, while only `wave-0003` ordinal 3 may load one explicit reviewed duplicate predecessor. After all infrastructure tests are green against the still-two-wave registry, one final producer run is captured into immutable Stage-A material, the empty wave is frozen, and registry pin plus Gradle inputs are registered before the public bundle is regenerated and verified.

**Tech Stack:** Python 3 standard library and `unittest`; Kotlin Gradle DSL; Gradle Wrapper; canonical UTF-8 JSON/NDJSON; SHA-256; PowerShell on Windows; Java 17 for Gradle verification.

**Spec:** `docs/superpowers/specs/2026-09-02-reviewed-duplicate-ownership-wave-design.md`, approved byte identity `e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55`.

## Global Constraints

- Recompute the spec hash before Task 1 and require exactly `e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55`.
- The live pre-registration registry must remain the canonical two-wave bytes with SHA-256 `555bfed91df4888611380a8c9a7ed9f094fd87a661c20fcc5eee6be87844c87b` until Task 7.
- Do not modify either `RetrievalOrderService` Java source, either focused Java test, `DynamicRetrievalHandlerChain.java`, or any `RagControlRuntimeAdapter` source/test file.
- Do not run the source-edit three-way preflight in this plan because no application-source mutation is authorized. A future separately approved source-repair plan must run it before any Java edit.
- Do not globally reclassify `DUPLICATE_FQCN_SOURCE_COLLISION`; its current public contract stays `REVIEW_REQUIRED / HOLD`.
- Wave 3 contains exactly one predecessor and it remains a byte-derived core row from the final Stage-A ledger.
- Wave-3 registration creates zero-byte `repair-progress.jsonl` and zero-byte `journal.jsonl`; it creates no target-admission or closure proof.
- Admission and registration add zero `VERIFIED_CLOSED` credit. Expected registered state is three waves, fourteen events, five rejected groups, nine verified groups, and one current `OPEN / ELIGIBLE` group, assuming no unrelated detector drift.
- Waves 1 and 2 retain their exact v1 intake, progress, event, proof, event-baseline, journal, and descriptor bytes.
- V2 intake is accepted only for `wave-0003`, ordinal `3`, `admissionMode=EXPLICIT_REVIEW_PROMOTION`, and one `REVIEW_REQUIRED / HOLD / DUPLICATE_FQCN_SOURCE_COLLISION` row.
- V1 and v2 exact field sets are disjoint validation branches. Unknown, mixed, borrowed, or cross-wave versions fail before overlay or publication.
- `proposedRepairTargetPath` is not source-edit authority. Live wave-3 registration must contain no `repair-target-admission.json`, `detector-red-summary.json`, `compile-baseline.json`, or `owner-contract.json`.
- `DISAPPEARED` support is dormant in this plan. Infrastructure tests must prove its detector-level contract, but no live v2 progress or event is appended.
- Use Java 17, `AWX_SPLIT_BUILD_OUTPUTS=1`, stable host ID `desktop-reviewed-duplicate-wave3`, an isolated `GRADLE_USER_HOME`, and an isolated Gradle `--project-cache-dir` for every Gradle command in Tasks 6-8.
- Do not run Browser, Computer, Supabase, provider, database, deployment, credential, commit, push, or destructive operations.
- Commit authority has not been granted. Replace every normal Superpowers commit checkpoint with a hash/status checkpoint; do not stage or commit.
- Preserve all unrelated dirty-tree entries and user hunks. Apply patches only to the files declared by the current task.
- Every failure reports `holdScope`, `firstBlockingRule`, hash- or count-only `blockingEvidence`, `independentWorkCompleted`, and `repositoryWideHold=false` unless all authorized work is unsafe.
- Run Python with `-B -X utf8` so verification does not create bytecode caches.

## File Structure And Ownership

- Modify `scripts/dynamic_rag_quant_audit.py`: sole production owner of schema constants, strict intake dispatch, target-admission validation, v2 progress/event target binding, aggregate history, overlay, and full public-bundle validation.
- Modify `scripts/test_dynamic_rag_quant_audit.py`: fixture builders and causal RED/GREEN coverage for exact v1/v2 dispatch, target admission, history aggregation, Gradle input registration, rollback, and publication.
- Modify `scripts/test_source_health_scorecard.py`: registered-wave fixture compatibility and delegation assertions only.
- Do not modify `scripts/source_health_scorecard.py` unless a new failing delegation test proves its existing production-loader call is insufficient. A second intake classifier is forbidden.
- Modify `build.gradle.kts` only in Task 7: exact wave-3 journal, five intake files, progress file, and proof filename allowlists on both public tasks with `PathSensitivity.RELATIVE`.
- Modify `verification/structural-repair-waves/registry.json` only in Task 7: append one ordinal-3 descriptor and preserve the first two descriptor objects byte-for-byte at the semantic-object level.
- Create `.superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json` in Task 6: v2 summary and transitive hashes.
- Create `.superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl` in Task 6: one exact envelope-free Stage-A ledger row.
- Create `.superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json` in Task 6: exactly five frozen paths.
- Create `.superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json` in Task 6: reviewed-admission identity and proposal, not source authority.
- Create `.superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json` in Task 6: normalized count/hash/FQCN/path evidence.
- Create `.superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl` in Task 6 as exactly zero bytes.
- Create `verification/structural-repair-waves/wave-0003/journal.jsonl` in Task 6 as exactly zero bytes.
- Do not create any file under `.superpowers/sdd/structural-repair-waves/wave-0003/proofs/` in this implementation.

---

### Task 1: Immutable Preflight And Approved-Boundary Checkpoint

**Files:**

- Read: `docs/superpowers/specs/2026-09-02-reviewed-duplicate-ownership-wave-design.md`
- Read: `scripts/dynamic_rag_quant_audit.py`
- Read: `scripts/test_dynamic_rag_quant_audit.py`
- Read: `scripts/test_source_health_scorecard.py`
- Read: `build.gradle.kts`
- Read: `verification/structural-repair-waves/registry.json`
- Read: wave-1 and wave-2 intake/progress/journal/proof artifacts
- Read: the five future target paths and the two RagControl HOLD paths
- Modify: none

**Interfaces:**

- Consumes: approved spec SHA-256 and the current two-wave registry.
- Produces: a task-local evidence packet containing branch, HEAD, staged count, index-lock state, PatchDrop count, source-lease count, immutable hashes, target hashes/states, and the first blocking reason if any. It is command evidence, not a new repository file.

- [ ] **Step 1: Verify root, branch, lock, pending-patch, and lease gates**

Run from `C:\AbandonWare\demo-1\demo-1\src`:

```powershell
$repoRoot = (Resolve-Path -LiteralPath '.').Path
$branch = git branch --show-current
$head = git rev-parse HEAD
$stagedCount = @(git diff --cached --name-only).Count
$indexLock = Test-Path -LiteralPath '.git\index.lock'
$topLevelPatchCount = @(
    Get-ChildItem -LiteralPath '__patch_drop__' -File -Filter '*.patch' -ErrorAction SilentlyContinue
).Count
$activeLeaseCount = @(
    Get-ChildItem -LiteralPath '__patch_drop__\source-edit-locks' -Directory -ErrorAction SilentlyContinue
).Count
[pscustomobject]@{
    repoRoot = $repoRoot
    branch = $branch
    head = $head
    stagedCount = $stagedCount
    indexLock = $indexLock
    topLevelPatchCount = $topLevelPatchCount
    activeLeaseCount = $activeLeaseCount
} | Format-List
```

Expected: branch is `codex/owned-runtime-browser-restart`; staged count is `0`; index lock is `False`; top-level patch count and active source-edit lease count are `0`. A mismatch is a lane-local HOLD before any write.

- [ ] **Step 2: Verify the approved spec and pre-registration registry identities**

```powershell
$spec = 'docs/superpowers/specs/2026-09-02-reviewed-duplicate-ownership-wave-design.md'
$registry = 'verification/structural-repair-waves/registry.json'
$specHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $spec).Hash.ToLowerInvariant()
$registryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $registry).Hash.ToLowerInvariant()
if ($specHash -ne 'e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55') {
    throw 'approved-spec-drift'
}
if ($registryHash -ne '555bfed91df4888611380a8c9a7ed9f094fd87a661c20fcc5eee6be87844c87b') {
    throw 'closure-registry-preimage-drift'
}
$registryValue = Get-Content -LiteralPath $registry -Raw -Encoding utf8 | ConvertFrom-Json
if (@($registryValue.waves).Count -ne 2) { throw 'expected-two-wave-registry' }
[pscustomobject]@{specSha256=$specHash;registrySha256=$registryHash;waveCount=2} | Format-List
```

Expected: both exact hashes match and the live registry contains two descriptors.

- [ ] **Step 3: Freeze infrastructure, historical-wave, candidate, and HOLD-path hashes**

Use this exact manifest command; retain only path, byte count, Git state, and SHA-256:

```powershell
$paths = @(
    'scripts/dynamic_rag_quant_audit.py',
    'scripts/test_dynamic_rag_quant_audit.py',
    'scripts/source_health_scorecard.py',
    'scripts/test_source_health_scorecard.py',
    'build.gradle.kts',
    'verification/structural-repair-waves/registry.json',
    '.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/intake-summary.json',
    '.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/eligible-groups.jsonl',
    '.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/target-preimages.json',
    '.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/repair-progress.jsonl',
    'verification/structural-repair-closure-journal.jsonl',
    '.superpowers/sdd/structural-repair-waves/wave-0002/intake/intake-summary.json',
    '.superpowers/sdd/structural-repair-waves/wave-0002/intake/eligible-groups.jsonl',
    '.superpowers/sdd/structural-repair-waves/wave-0002/intake/target-preimages.json',
    '.superpowers/sdd/structural-repair-waves/wave-0002/repair-progress.jsonl',
    'verification/structural-repair-waves/wave-0002/journal.jsonl',
    'app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java',
    'main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java',
    'main/java/com/example/lms/strategy/RetrievalOrderService.java',
    'src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java',
    'src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java',
    'main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java',
    'src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java'
)
$manifest = foreach ($path in $paths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "required-preimage-missing:$path" }
    $status = @(git status --short -- $path)
    [pscustomobject]@{
        path = $path
        gitState = if ($status.Count -eq 0) {'clean'} else {$status[0].Substring(0,2).Trim()}
        sizeBytes = (Get-Item -LiteralPath $path).Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    }
}
$manifest | Format-Table -AutoSize
```

Expected: all paths exist; no wave-3 path exists yet. Preserve this output for per-task postimage comparisons.

- [ ] **Step 4: Prove current validators are green before code changes**

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
python -B -X utf8 -c "from pathlib import Path; from scripts.dynamic_rag_quant_audit import load_and_validate_current_bundle; from scripts.source_health_scorecard import validate_current_quant_bundle; root=Path('.').resolve(); bundle=load_and_validate_current_bundle(root=root); metrics=validate_current_quant_bundle(root); assert metrics == bundle.metrics; print('currentBundle=PASS'); print('waveCount=' + str(metrics['closureHistorySummary']['waveCount']))"
```

Expected: `currentBundle=PASS` and `waveCount=2`.

- [ ] **Step 5: Record the no-write checkpoint**

Require unchanged staged count, index lock, top-level PatchDrop count, source-lease count, all five candidate target hashes, and both RagControl hashes. Do not stage or commit.

---

### Task 2: Strict Intake-V2 Admission Loader

**Files:**

- Modify: `scripts/dynamic_rag_quant_audit.py:20-33,73-103,170-309,363-419,2514-2688`
- Modify: `scripts/test_dynamic_rag_quant_audit.py:22-58,164-208,531-641,993-1543`
- Test: `scripts/test_dynamic_rag_quant_audit.py`

**Interfaces:**

- Consumes: existing `ClosureWaveDescriptor`, canonical parser helpers, `_validate_ledger_core_rows`, v1 target-preimage schema, and the approved design hash.
- Produces:

```python
@dataclass(frozen=True)
class ClosureWaveIntake:
    schema_version: str
    predecessor_rows: tuple[dict[str, Any], ...]
    targets: dict[str, dict[str, Any]]
    summary: dict[str, Any]
    admission_decision: dict[str, Any] | None
    admission_decision_sha256: str | None
    duplicate_evidence: dict[str, Any] | None
    duplicate_evidence_sha256: str | None
```

- Exact function signature: `_load_closure_intake(proof_root: Path, descriptor: ClosureWaveDescriptor) -> ClosureWaveIntake`.

- V1 returns both optional admission objects and hashes as `None`.
- V2 returns exact canonical decision/evidence objects and their raw SHA-256 values.

- [ ] **Step 1: Add the v2 fixture writer and causal failing tests**

Add this helper contract to `scripts/test_dynamic_rag_quant_audit.py`; use `canonical_bytes` for every file:

```python
def write_reviewed_duplicate_intake_v2(
    fx: FixturePaths,
    predecessor_bundle: object,
    predecessor_row: dict[str, object],
    *,
    duplicate_report_sha256: str,
    target_rows: list[dict[str, object]],
) -> dict[str, object]:
    evidence = {
        "schemaVersion": "awx.structural-repair-duplicate-evidence.v1",
        "sourceIssueId": predecessor_row["issueId"],
        "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
        "fqcn": "com.example.lms.strategy.RetrievalOrderService",
        "canonicalOwnerPath": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "compatibilityCopyPath": "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "sourceCollisionCount": 1,
        "generatedExcludeCount": 1,
        "hardExcludeCount": 0,
        "packagedActiveCount": 0,
        "generatedExcludePattern": "com/example/lms/strategy/RetrievalOrderService*",
        "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
        "rawReportSha256": duplicate_report_sha256,
        "secretPatternHitCount": 0,
    }
    evidence_bytes = canonical_bytes(evidence)
    decision = {
        "schemaVersion": "awx.structural-repair-admission-decision.v1",
        "decisionType": "REVIEW_REQUIRED_DUPLICATE_OWNER",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceIssueId": predecessor_row["issueId"],
        "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
        "sourceEvidenceFingerprint": predecessor_row["evidenceFingerprint"],
        "sourceCategory": predecessor_row["category"],
        "sourceFixEligibility": predecessor_row["fixEligibility"],
        "sourceStatus": predecessor_row["status"],
        "sourceNumericEvidence": predecessor_row["numericEvidence"],
        "canonicalOwnerPath": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "proposedRepairTargetPath": "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "repairTargetAdmissionRequired": True,
        "activeCallPath": "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
        "behaviorTestPath": "src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java",
        "ownerContractTestPath": "src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java",
        "duplicateEvidenceSha256": sha256_hex(evidence_bytes),
        "approvedDesignSha256": "e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55",
        "ragControlExcluded": True,
    }
    decision["decisionId"] = identity_hash(decision, "decisionId")
    decision_bytes = canonical_bytes(decision)
    summary = {
        "schemaVersion": "awx.structural-repair-intake.v2",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceLedgerPayloadSha256": predecessor_bundle.metrics["artifactLinks"]["ledgerPayloadSha256"],
        "sourceMetricsSemanticHash": predecessor_bundle.metrics["semanticArtifactHash"],
        "sourceIssueIds": [predecessor_row["issueId"]],
        "rootCauseGroupIds": [predecessor_row["rootCauseGroupId"]],
        "evidenceFingerprints": [predecessor_row["evidenceFingerprint"]],
        "sourceBranch": predecessor_bundle.baseline["branch"],
        "sourceHead": predecessor_bundle.baseline["head"],
        "admissionMode": "EXPLICIT_REVIEW_PROMOTION",
        "admissionDecisionSha256": sha256_hex(decision_bytes),
        "duplicateEvidenceSha256": sha256_hex(evidence_bytes),
    }
    intake = fx.closure_proof_root / "intake"
    write_bytes(intake, "duplicate-evidence.json", evidence_bytes)
    write_bytes(intake, "admission-decision.json", decision_bytes)
    write_bytes(intake, "eligible-groups.jsonl", canonical_bytes(predecessor_row))
    write_bytes(intake, "target-preimages.json", canonical_bytes({
        "schemaVersion": "awx.structural-repair-target-preimages.v1",
        "sourceHead": predecessor_bundle.baseline["head"],
        "canonicalBranch": predecessor_bundle.baseline["branch"],
        "isolatedBranch": predecessor_bundle.baseline["branch"],
        "targets": target_rows,
    }))
    write_bytes(intake, "intake-summary.json", canonical_bytes(summary))
    (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(b"")
    fx.closure_journal.write_bytes(b"")
    return decision
```

Add these exact tests:

- `test_wave_three_v2_intake_accepts_one_exact_reviewed_duplicate_predecessor`: create the exact root/app pair, build a current duplicate ledger row, write v2 intake, direct-load `wave-0003`, and assert one unchanged `REVIEW_REQUIRED / HOLD` predecessor with empty events, admissions, and proof pairs.
- `test_wave_three_v2_intake_rejects_wrong_descriptor_row_decision_and_evidence`: rebuild a fresh fixture per subtest, mutate one descriptor/row/decision/evidence field, recompute only the immediately containing hash when testing a semantic mismatch, and assert the specified fail-closed reason.
- `test_v1_intake_rejects_v2_only_fields_and_preserves_v1_contract`: add each v2-only summary field to a v1 fixture and require `closure-intake-mismatch`, then load untouched wave-1 bytes and assert the frozen v1 predecessor/event/proof hashes.
- `test_wave_three_v2_intake_rejects_ragcontrol_identity_or_target`: substitute the RagControl issue, group, symbol, source path, and each target path in separate fresh fixtures and require rejection; a fixture containing only `ragControlExcluded=True` must pass.

The positive fixture uses a synthetic duplicate row whose category contract naturally resolves to `HIGH / REVIEW_REQUIRED / HOLD`; do not rewrite it to `ELIGIBLE / OPEN`. Negative subtests mutate one field at a time and require `closure-admission-invalid` for decision/evidence failures or `closure-intake-mismatch` for summary/descriptor/predecessor failures.

- [ ] **Step 2: Run the intake tests and verify RED**

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_accepts_one_exact_reviewed_duplicate_predecessor `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_rejects_wrong_descriptor_row_decision_and_evidence `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_v1_intake_rejects_v2_only_fields_and_preserves_v1_contract `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_rejects_ragcontrol_identity_or_target
```

Expected: FAIL because v2 constants, fields, and dispatch do not exist; the first failure must be at the absent v2 contract rather than fixture setup.

- [ ] **Step 3: Add exact v2 constants and field sets without renaming v1 owners**

Add the following alongside the current v1 constants. Keep `CLOSURE_EVENT_SCHEMA`, `CLOSURE_PROGRESS_SCHEMA`, `CLOSURE_INTAKE_SCHEMA`, and their field sets unchanged as v1 compatibility owners:

```python
CLOSURE_EVENT_V2_SCHEMA = "awx.structural-repair-closure-event.v2"
CLOSURE_PROGRESS_V2_SCHEMA = "awx.structural-repair-progress.v2"
CLOSURE_INTAKE_V2_SCHEMA = "awx.structural-repair-intake.v2"
CLOSURE_ADMISSION_DECISION_SCHEMA = "awx.structural-repair-admission-decision.v1"
CLOSURE_DUPLICATE_EVIDENCE_SCHEMA = "awx.structural-repair-duplicate-evidence.v1"
CLOSURE_TARGET_ADMISSION_SCHEMA = "awx.structural-repair-target-admission.v1"
CLOSURE_TARGET_ADMISSION_PROOF_SCHEMA = "awx.structural-repair-target-admission-proof.v1"
WAVE_THREE_APPROVED_DESIGN_SHA256 = (
    "e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55"
)
ADMISSION_DECISION_RELATIVE = Path("intake/admission-decision.json")
DUPLICATE_EVIDENCE_RELATIVE = Path("intake/duplicate-evidence.json")

CLOSURE_INTAKE_V2_SUMMARY_FIELDS = CLOSURE_INTAKE_SUMMARY_FIELDS | {
    "admissionMode",
    "admissionDecisionSha256",
    "duplicateEvidenceSha256",
}
CLOSURE_ADMISSION_DECISION_FIELDS = frozenset({
    "schemaVersion", "decisionId", "decisionType", "sourceBaselineId",
    "sourceIssueId", "rootCauseGroupId", "sourceEvidenceFingerprint",
    "sourceCategory", "sourceFixEligibility", "sourceStatus",
    "sourceNumericEvidence", "canonicalOwnerPath", "proposedRepairTargetPath",
    "repairTargetAdmissionRequired", "activeCallPath", "behaviorTestPath",
    "ownerContractTestPath", "duplicateEvidenceSha256", "approvedDesignSha256",
    "ragControlExcluded",
})
CLOSURE_DUPLICATE_EVIDENCE_FIELDS = frozenset({
    "schemaVersion", "sourceIssueId", "rootCauseGroupId", "fqcn",
    "canonicalOwnerPath", "compatibilityCopyPath", "sourceCollisionCount",
    "generatedExcludeCount", "hardExcludeCount", "packagedActiveCount",
    "generatedExcludePattern", "proofCommand", "rawReportSha256",
    "secretPatternHitCount",
})
```

Add `closure-admission-invalid` to `REASON_CODES`.

- [ ] **Step 4: Implement strict descriptor-local intake dispatch**

Refactor `_load_closure_intake` only after the three descriptor-pinned base files are read, hash-checked, and canonically parsed. Dispatch exactly as follows:

```python
if summary_value.get("schemaVersion") == CLOSURE_INTAKE_SCHEMA:
    if set(summary_value) != CLOSURE_INTAKE_SUMMARY_FIELDS:
        raise AuditContractError("closure-intake-mismatch")
    return _validate_closure_intake_v1(
        descriptor=descriptor,
        summary=summary_value,
        predecessor_rows=predecessor_rows,
        target_value=target_value,
    )
if summary_value.get("schemaVersion") == CLOSURE_INTAKE_V2_SCHEMA:
    if descriptor.wave_id != "wave-0003" or descriptor.ordinal != 3:
        raise AuditContractError("closure-intake-mismatch")
    return _validate_reviewed_duplicate_intake_v2(
        proof_root=proof_root,
        descriptor=descriptor,
        summary=summary_value,
        predecessor_rows=predecessor_rows,
        target_value=target_value,
    )
raise AuditContractError("closure-intake-mismatch")
```

`_validate_closure_intake_v1` must preserve the current exact v1 checks and ordering: descriptor identity, branch/head linkage, `_validate_ledger_core_rows`, nonempty broad-catch `ELIGIBLE / OPEN` rows, ordered identity lists, nonempty sorted target rows.

`_validate_reviewed_duplicate_intake_v2` must require:

```python
len(predecessor_rows) == 1
descriptor.wave_id == "wave-0003"
descriptor.ordinal == 3
summary["admissionMode"] == "EXPLICIT_REVIEW_PROMOTION"
row["category"] == "DUPLICATE_FQCN_SOURCE_COLLISION"
row["severity"] == "HIGH"
row["fixEligibility"] == "REVIEW_REQUIRED"
row["status"] == "HOLD"
row["supersedes"] == []
row["symbol"] == "com.example.lms.strategy.RetrievalOrderService"
row["path"] == "main/java/com/example/lms/strategy/RetrievalOrderService.java"
row["numericEvidence"] == {
    "duplicateFqcnGeneratedExcludeCount": 1,
    "duplicateFqcnHardExcludeCount": 0,
    "duplicateFqcnPackagedActiveCount": 0,
    "duplicateFqcnSourceCollisionCount": 1,
}
```

Read only `ADMISSION_DECISION_RELATIVE` and `DUPLICATE_EVIDENCE_RELATIVE`; compare raw hashes to both summary fields; canonical-parse; require exact field sets; recompute `decisionId`; validate every source field against the predecessor; require the five exact target paths in case-insensitive sorted order; reject every RagControl path, issue, group, symbol, predecessor, or target. The only RagControl-related value allowed is `ragControlExcluded is True`.

- [ ] **Step 5: Update registered-wave fixture copying by schema**

Change `seed_registered_empty_histories` so it always copies the existing three intake files plus progress, then canonical-parses `intake-summary.json`; for v2 only, also copy `admission-decision.json` and `duplicate-evidence.json`. Reject unknown schemas in the fixture helper instead of silently omitting files.

- [ ] **Step 6: Run intake RED/GREEN and v1 parity tests**

Run the four tests from Step 2, then:

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_preserves_wave_one_predecessors_events_and_proofs `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_validates_empty_registered_wave_intake_and_progress `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_rejects_empty_wave_with_missing_or_modified_intake
```

Expected: all seven tests PASS; wave-1 fixed event/proof/terminal hashes remain unchanged.

- [ ] **Step 7: Review the Task-2 diff and record a no-commit checkpoint**

```powershell
git diff --check -- scripts/dynamic_rag_quant_audit.py scripts/test_dynamic_rag_quant_audit.py
git diff --stat -- scripts/dynamic_rag_quant_audit.py scripts/test_dynamic_rag_quant_audit.py
git status --short -- scripts/dynamic_rag_quant_audit.py scripts/test_dynamic_rag_quant_audit.py
```

Expected: only the two declared files changed; no source, registry, Gradle, public artifact, or wave-3 file changed.

---

### Task 3: Repair-Target Admission And Strict V2 Progress/Event Binding

**Files:**

- Modify: `scripts/dynamic_rag_quant_audit.py:170-309,383-419,2691-2842,2979-3180`
- Modify: `scripts/test_dynamic_rag_quant_audit.py:644-850,1364-1543,2440-2673`
- Test: `scripts/test_dynamic_rag_quant_audit.py`

**Interfaces:**

- Consumes: `ClosureWaveIntake`, canonical proof loader, fixed group-scoped paths, existing v1 proof/progress/event contracts.
- Produces:

```python
@dataclass(frozen=True)
class ClosureTargetAdmission:
    row: dict[str, Any]
    raw_sha256: str
    proof_pairs: tuple[tuple[str, str], ...]

@dataclass(frozen=True)
class ClosureProgressEntry:
    row: dict[str, Any]
    raw_sha256: str
    target_admission: ClosureTargetAdmission | None
```

- Exact function signature: `_load_progress_rows(*, proof_root: Path, intake: ClosureWaveIntake) -> dict[str, ClosureProgressEntry]`.

- V1 progress entries have `target_admission=None`.
- V2 progress entries carry one fully validated target admission.

- [ ] **Step 1: Write target-admission and v2 progress/event failing tests**

Add these exact tests:

- `test_empty_wave_three_requires_all_target_admission_files_absent`: load the empty v2 fixture successfully, then create each of the four fixed future proof filenames in a separate fresh fixture and require `closure-admission-invalid`.
- `test_v2_progress_requires_exact_target_admission_and_three_evidence_files`: construct one canonical target-admission row and three role proofs, append one matching v2 progress row, and assert each ID/hash/path; mutate every required link in separate subtests.
- `test_v2_event_binds_historical_source_to_independently_admitted_target`: append one matching v2 event whose `sourcePath` is the root owner and `repairTargetPath` is the admitted app source, then assert the postimage read occurs at the app path and the historical identity remains rooted at the predecessor.
- `test_v1_progress_and_event_reject_v2_fields_and_v2_rejects_v1_shape`: inject every v2-only field into fresh v1 rows and remove every required v2-only field from fresh v2 rows; require the progress or journal reason family without ignored rows.
- `test_v2_event_baseline_rejects_non_target_drift_and_body_only_disappearance_claim`: mutate each non-target baseline row and require proof rejection; separately keep the original evidence fingerprint in current rows and require `closure-fingerprint-active`.

The positive event fixture must keep `sourcePath` on the root owner while `repairTargetPath` points to the independently admitted app source. Negative subtests cover missing proof files, wrong group/issue/design/decision hashes, a call-path or test repair target, `transformationMode` other than `IN_PLACE_EXISTING_FILE`, zero-exit detector RED, nonzero-exit baseline proof, unknown fields, noncanonical bytes, absolute paths, secret hits, mixed schemas, unchanged pre/post hashes, and `fingerprintDisposition` other than `DISAPPEARED` for v2.

- [ ] **Step 2: Run the new target-binding tests and verify RED**

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_empty_wave_three_requires_all_target_admission_files_absent `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_v2_progress_requires_exact_target_admission_and_three_evidence_files `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_v2_event_binds_historical_source_to_independently_admitted_target `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_v1_progress_and_event_reject_v2_fields_and_v2_rejects_v1_shape `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_v2_event_baseline_rejects_non_target_drift_and_body_only_disappearance_claim
```

Expected: FAIL at missing v2 target-admission/progress/event support.

- [ ] **Step 3: Add exact v2 and target-admission field contracts**

```python
CLOSURE_PROGRESS_V2_FIELDS = CLOSURE_PROGRESS_FIELDS | {
    "admissionDecisionId", "admissionDecisionSha256",
    "targetAdmissionId", "targetAdmissionSha256", "repairTargetPath",
}
CLOSURE_EVENT_V2_FIELDS = CLOSURE_EVENT_FIELDS | {
    "admissionDecisionId", "admissionDecisionSha256",
    "targetAdmissionId", "targetAdmissionSha256", "repairTargetPath",
}
CLOSURE_TARGET_ADMISSION_FIELDS = frozenset({
    "schemaVersion", "targetAdmissionId", "rootCauseGroupId", "sourceIssueId",
    "admissionDecisionId", "admissionDecisionSha256", "repairTargetPath",
    "transformationMode", "approvedSourceDesignSha256", "detectorRedProofId",
    "detectorRedProofSha256", "compileBaselineProofSha256",
    "ownerContractProofSha256", "secretPatternHitCount",
})
CLOSURE_TARGET_ADMISSION_PROOF_FIELDS = frozenset({
    "schemaVersion", "proofRole", "rootCauseGroupId", "sourceIssueId",
    "commandToken", "exitCode", "result", "assertionCount", "outputSha256",
    "secretPatternHitCount",
})
TARGET_ADMISSION_SUFFIXES = {
    "admission": "repair-target-admission",
    "detector": "detector-red-summary",
    "compile": "compile-baseline",
    "owner": "owner-contract",
}
```

Do not broaden the existing `PROOF_ID_RE`; normal v1 red/green/event-baseline IDs keep their current exact regex.

- [ ] **Step 4: Implement fixed-path target-admission proof validation**

Add pure path construction and validation helpers:

```python
def _target_admission_proof_id(group_id: str, suffix: str) -> str:
    if not _is_lower_hex64(group_id) or suffix not in TARGET_ADMISSION_SUFFIXES.values():
        raise AuditContractError("closure-admission-invalid")
    return f"proofs/{group_id}/{suffix}.json"
```

- Exact function signature: `_load_target_admission(*, proof_root: Path, intake: ClosureWaveIntake, expected_id: object, expected_sha256: object) -> ClosureTargetAdmission`.

`_load_target_admission` must canonical-read `repair-target-admission.json`, recompute its raw hash and `targetAdmissionId`, match the intake decision and selected predecessor, require the repair path to be one of the two frozen Java source paths, and reject call-path/test targets. It then canonical-loads exactly the detector, compile, and owner files. Each proof uses `awx.structural-repair-target-admission-proof.v1`; roles and outcomes are:

```python
{
    "DETECTOR_RED": {"result": "EXPECTED_FAIL", "zeroExit": False},
    "COMPILE_BASELINE": {"result": "PASS", "zeroExit": True},
    "OWNER_CONTRACT": {"result": "PASS", "zeroExit": True},
}
```

Return proof pairs keyed by all four exact relative paths and sorted by `(path, hash)`.

- [ ] **Step 5: Implement strict progress dispatch**

Change `_load_progress_rows` to accept `intake`. For each canonical row:

```python
if row.get("schemaVersion") == CLOSURE_PROGRESS_SCHEMA:
    if intake.schema_version != CLOSURE_INTAKE_SCHEMA or set(row) != CLOSURE_PROGRESS_FIELDS:
        raise AuditContractError("closure-progress-mismatch")
    target_admission = None
elif row.get("schemaVersion") == CLOSURE_PROGRESS_V2_SCHEMA:
    if intake.schema_version != CLOSURE_INTAKE_V2_SCHEMA or set(row) != CLOSURE_PROGRESS_V2_FIELDS:
        raise AuditContractError("closure-progress-mismatch")
    target_admission = _load_target_admission(
        proof_root=proof_root,
        intake=intake,
        expected_id=row.get("targetAdmissionId"),
        expected_sha256=row.get("targetAdmissionSha256"),
    )
else:
    raise AuditContractError("closure-progress-mismatch")
```

For v2, both decision fields equal the intake decision, `repairTargetPath` equals the target-admission row, and target preimage equals that frozen path's hash. Keep the current patch-state formula and changed-postimage/nonzero-scoped-diff rules.

When progress is empty and intake is v2, require all four target-admission filenames to be absent. A stray file is `closure-admission-invalid`; it is not ignored private evidence.

- [ ] **Step 6: Implement strict event dispatch and target-path validation**

Inside `_load_closure_wave_from_journal_bytes`, use the intake object and dispatch every event exactly:

```python
if event.get("schemaVersion") == CLOSURE_EVENT_SCHEMA:
    expected_fields = CLOSURE_EVENT_FIELDS
    expected_intake = CLOSURE_INTAKE_SCHEMA
    changed_target_path = event.get("sourcePath")
elif event.get("schemaVersion") == CLOSURE_EVENT_V2_SCHEMA:
    expected_fields = CLOSURE_EVENT_V2_FIELDS
    expected_intake = CLOSURE_INTAKE_V2_SCHEMA
    changed_target_path = event.get("repairTargetPath")
else:
    raise AuditContractError("closure-journal-malformed")
if set(event) != expected_fields or intake.schema_version != expected_intake:
    raise AuditContractError("closure-journal-malformed")
```

For v2:

- require a `VERIFIED_CLOSED` event and `fingerprintDisposition=DISAPPEARED`;
- bind both decision fields to intake;
- bind all five target-admission fields across event, progress, and target-admission row;
- validate pre/post/current bytes against `repairTargetPath`, never silently fall back to `sourcePath`;
- keep historical source identity fields bound to the frozen root-owner predecessor;
- validate event-baseline rows in exact five-path order;
- require every non-target event-baseline row's hash, size, and Git state to equal its frozen preimage;
- require the changed row hash to equal `targetPostimageSha256` and its live file hash;
- add the target-admission file and its three evidence files to `proof_pairs` only when a v2 event references them.

Do not weaken any v1 event or progress check.

- [ ] **Step 7: Prove detector-level disappearance semantics in the overlay fixture**

Add a focused test that constructs a valid synthetic v2 event, then calls `apply_closure_overlay` twice:

```python
self.assert_reason(
    "closure-fingerprint-active",
    audit.apply_closure_overlay,
    current_rows_with_same_fingerprint,
    history,
    baseline_id=current_baseline_id,
)
overlaid = audit.apply_closure_overlay(
    current_rows_without_same_fingerprint,
    history,
    baseline_id=current_baseline_id,
)
self.assertEqual("VERIFIED_CLOSED", next(
    row["status"] for row in overlaid if row["issueId"] == predecessor["issueId"]
))
```

This is structural contract proof only; do not edit either live duplicate Java file.

- [ ] **Step 8: Run focused and existing closure tests**

Run the five tests from Step 2, the overlay test from Step 7, and:

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_valid_rejected_event_preserves_identity_without_credit `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_valid_closed_event_requires_matching_patch_state_and_earns_one_credit `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_prospectively_validates_journal_bytes_without_writing
```

Expected: all tests PASS; current v1 fixtures and proof identities remain green.

- [ ] **Step 9: Record the Task-3 no-commit checkpoint**

Run `git diff --check` on the two declared files, verify no wave-3 path exists, verify the two-wave registry hash remains `555bfed91df4888611380a8c9a7ed9f094fd87a661c20fcc5eee6be87844c87b`, and rehash all five candidate files plus both RagControl files against Task 1.

---

### Task 4: Aggregate History, Public Validation, And Source-Health Delegation

**Files:**

- Modify: `scripts/dynamic_rag_quant_audit.py:383-419,3323-3552,3555-3864`
- Modify: `scripts/test_dynamic_rag_quant_audit.py:1544-2230,2337-2439`
- Modify: `scripts/test_source_health_scorecard.py:51-78,80-189,1062-1158`
- Test: `scripts/test_dynamic_rag_quant_audit.py`
- Test: `scripts/test_source_health_scorecard.py`
- Do not modify: `scripts/source_health_scorecard.py`

**Interfaces:**

- Consumes: validated per-wave v1/v2 `ClosureWaveHistory` objects.
- Produces: the existing `ClosureHistory` and `closureHistorySummary` schemas with unchanged field sets and deterministic hashes; target-admission proof pairs participate in `proofSetSha256` only when a future v2 event references them.

- [ ] **Step 1: Add three-wave aggregation and delegation failing tests**

Add these exact dynamic-audit tests:

- `test_history_accepts_wave_one_wave_two_and_empty_reviewed_duplicate_wave_three`: aggregate valid v1 wave 1, valid v1 wave 2, and empty v2 wave 3; assert three waves and unchanged event/rejected/verified counts.
- `test_history_reconstructs_v2_target_admission_proof_pairs_without_extras`: construct one future v2 event fixture and assert the target-admission file plus detector/compile/owner hashes participate in the deterministic proof set exactly once.
- `test_history_rejects_cross_wave_or_unreferenced_v2_target_admission_pairs`: move, duplicate, or add each supporting pair in fresh fixtures and require `closure-wave-conflict`.
- `test_later_v2_wave_failure_preserves_all_prior_public_output_bytes`: tamper the v2 decision and duplicate-evidence bytes after staging and assert all three sentinel public outputs remain byte-identical.

Add these exact source-health tests:

- `test_current_quant_bundle_reconstructs_empty_reviewed_duplicate_wave_three`: seed a canonical three-wave fixture and assert production-loader call counts, exact history summary, and public object equality.
- `test_current_quant_bundle_rejects_tampered_wave_three_admission_bytes`: alter one canonical admission byte, run source-health main, require exit 2/cross-artifact-mismatch for this closure admission/history validation failure, and assert every prior scorecard/sidecar output remains byte-identical.

The first source-health test must assert that `load_closure_registry`, `load_closure_history`, and `load_and_validate_current_bundle` remain the production owners; do not introduce a source-health-only v2 parser.

- [ ] **Step 2: Run the six tests and verify RED**

Use one `python -B -X utf8 -m unittest` command naming all six selectors. Expected: aggregation or fixture-copy failures, not unrelated scorecard failures.

- [ ] **Step 3: Carry validated target-admission rows through wave history**

Extend `ClosureWaveHistory` with a defaulted final field so existing v1 constructors remain source-compatible:

```python
@dataclass(frozen=True)
class ClosureWaveHistory:
    descriptor: ClosureWaveDescriptor
    predecessor_rows: tuple[dict[str, Any], ...]
    all_events: tuple[dict[str, Any], ...]
    active_events: tuple[dict[str, Any], ...]
    journal_payload_sha256: str
    proof_pairs: tuple[tuple[str, str], ...]
    target_admissions: tuple[dict[str, Any], ...] = ()
```

The wave loader stores only target admissions actually referenced by v2 events, deduplicated by `targetAdmissionId` and sorted by that ID. Empty wave 3 and every v1 wave return `()`.

- [ ] **Step 4: Validate v2 supporting proof pairs during aggregate reconstruction**

For each v2 event, find exactly one carried target-admission row and independently recompute:

```python
target_admission_id = sha256_hex(canonical_json_bytes({
    key: value for key, value in admission.items() if key != "targetAdmissionId"
}))
target_admission_sha256 = sha256_hex(canonical_json_bytes(admission))
```

Require those values to equal the event. Reconstruct four exact proof pairs from the admission row and fixed group-scoped paths, add them to `referenced_proofs`, and retain the existing `proof_by_id == referenced_proofs` equality. Reject extra admissions, extra pairs, cross-wave IDs, duplicate target-admission IDs, or evidence hashes that disagree with the carried row.

The aggregate summary field set stays unchanged. With an empty third journal, `eventCount`, rejected count, and verified count remain unchanged while `waveCount` becomes 3 and registry/journal/proof set hashes change deterministically.

- [ ] **Step 5: Generalize only the source-health fixture seeding**

`_seed_empty_registered_history` continues to copy the live registry and calls the shared `seed_registered_empty_histories`. Update assertions so the expected empty summary derives `waveCount`, journal rows, and hashes from the copied registry. Do not hard-code three waves in the helper; the production registry is the authority.

The fixture must copy the two v2 intake-only files when its summary schema is v2. It must leave every journal and progress file zero bytes and create no target-admission evidence.

- [ ] **Step 6: Preserve public output bytes on late-wave validation failure**

Extend the existing rollback test with a third descriptor whose decision or normalized evidence raw bytes are changed after staging. Require `publish_bundle` to raise before replacing baseline, ledger, or metrics, and assert byte equality for all three prior outputs.

- [ ] **Step 7: Run focused aggregation, publication, and source-health tests**

Run the six new tests plus:

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_closure_history_accepts_valid_wave_one_plus_empty_wave_two `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_closure_history_rejects_duplicate_event_ids_across_waves `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_closure_history_rejects_cross_wave_supersession `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_later_wave_failure_preserves_all_prior_public_output_bytes `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_registry_backed_v2_history `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_main_current_linked_quant_bundle_writes_registry_backed_scorecard
```

Expected: all tests PASS and `scripts/source_health_scorecard.py` remains byte-identical to Task 1.

- [ ] **Step 8: Run both full Python modules**

```powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
```

Expected: both modules exit `0` with no failures, errors, or skips caused by this work.

- [ ] **Step 9: Record the Task-4 no-commit checkpoint**

Require clean `git diff --check` on the three declared files, unchanged two-wave registry hash, absent wave-3 paths, unchanged `scripts/source_health_scorecard.py`, unchanged candidate/RagControl hashes, staged count `0`, and no lock/lease/PatchDrop drift.

---

### Task 5: Two-Wave Infrastructure Green Gate Before Final Producer

**Files:**

- Read: all Task-2 through Task-4 postimages
- Read: current two-wave registry/history/public bundle
- Modify: none

**Interfaces:**

- Consumes: fully implemented strict v1/v2 production code and tests while the live registry still has only two waves.
- Produces: a green infrastructure checkpoint after which production bytes cannot change before the Task-6 final producer and Stage-A freeze.

- [ ] **Step 1: Verify Java 17 without changing persistent environment**

```powershell
java -version
```

Expected: major version 17. Otherwise HOLD the Gradle lane and continue only Python checks that do not claim Gradle readiness.

- [ ] **Step 2: Run the full Python contract surface again**

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
```

Expected: both exit `0`.

- [ ] **Step 3: Revalidate the current two-wave public bundle**

```powershell
python -B -X utf8 -c "from pathlib import Path; from scripts.dynamic_rag_quant_audit import load_closure_registry,load_closure_history,load_and_validate_current_bundle,CLOSURE_WAVE_REGISTRY_RELATIVE; from scripts.source_health_scorecard import validate_current_quant_bundle; root=Path('.').resolve(); registry=load_closure_registry(root=root,registry_path=CLOSURE_WAVE_REGISTRY_RELATIVE); assert len(registry.waves)==2; history=load_closure_history(root=root,registry=registry); bundle=load_and_validate_current_bundle(root=root); assert validate_current_quant_bundle(root)==bundle.metrics; print(history.summary)"
```

Expected: current bundle and source-health validation PASS with wave count 2.

- [ ] **Step 4: Freeze Task-5 production and test postimages**

Hash these exact files and retain the values in execution evidence:

```text
scripts/dynamic_rag_quant_audit.py
scripts/test_dynamic_rag_quant_audit.py
scripts/source_health_scorecard.py
scripts/test_source_health_scorecard.py
build.gradle.kts
verification/structural-repair-waves/registry.json
```

No file in this list may change between the final Task-6 producer invocation and completion of Task-6 wave-file writes. If it changes, discard Stage-A output and repeat Task 5 and Task 6.

- [ ] **Step 5: Confirm no wave-3 path and no source mutation**

Require all seven future wave-3 files absent, all four future target-admission proof files absent, candidate/RagControl hashes unchanged from Task 1, staged count `0`, and no index lock, active lease, or top-level patch.

---

### Task 6: Final Fresh Audit, Immutable Stage-A Capture, And Wave-3 Freeze

**Files:**

- Regenerate through producer: `verification/dynamic-rag-quant-audit-metrics.json`
- Regenerate through producer: `verification/structural-design-baseline.json`
- Regenerate through producer: `verification/structural-design-debt-ledger.jsonl`
- Generate as build evidence: `app/build/desktop-reviewed-duplicate-wave3/reports/dup-fqcn-evidence.json`
- Create: `.superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json`
- Create: `.superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl`
- Create: `.superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json`
- Create: `.superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json`
- Create: `.superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json`
- Create: `.superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl` as zero bytes
- Create: `verification/structural-repair-waves/wave-0003/journal.jsonl` as zero bytes
- Do not modify: production/test/Gradle/registry files

**Interfaces:**

- Consumes: final Task-5 production bytes and the two-wave registry.
- Produces: one immutable Stage-A tuple and a directly loadable but still-unregistered `ClosureWaveDescriptor` for wave 3.

- [ ] **Step 1: Configure isolated Desktop Gradle caches**

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-reviewed-duplicate-wave3'
$wave3CacheRoot = Join-Path `
    ([IO.Path]::GetTempPath()) `
    ("awx-wave3-" + [guid]::NewGuid().ToString("N"))
$env:GRADLE_USER_HOME = Join-Path $wave3CacheRoot 'gradle-user'
$wave3ProjectCache = Join-Path $wave3CacheRoot 'project-cache'
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME | Out-Null
New-Item -ItemType Directory -Path $wave3ProjectCache | Out-Null
```

Retain these process variables through Tasks 6-8. Do not delete or reuse another task's cache directory.

- [ ] **Step 2: Run the one final pre-registration producer invocation**

```powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave3ProjectCache dynamicRagQuantAudit
```

Expected: exit `0`. This is the final producer before wave-3 files are written. Do not invoke `sourceHealthScorecard`, `dynamicRagQuantAudit`, or any dependent Gradle task again until Task 7.

- [ ] **Step 3: Validate and print the exact canonical freeze material without writing**

Run the following read-only Python program immediately after Step 2. It prints the five nonempty file payloads and their hashes; it does not write them:

```powershell
@'
from __future__ import annotations

import copy
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from scripts import dynamic_rag_quant_audit as audit
from scripts import source_health_scorecard as score

root = Path(".").resolve()
design_path = root / "docs/superpowers/specs/2026-09-02-reviewed-duplicate-ownership-wave-design.md"
design_sha = hashlib.sha256(design_path.read_bytes()).hexdigest()
assert design_sha == "e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55"

bundle = audit.load_and_validate_current_bundle(root=root)
source_metrics = score.validate_current_quant_bundle(root)
assert source_metrics == bundle.metrics

core_rows = []
for envelope in bundle.ledger_rows:
    row = copy.deepcopy(envelope)
    for field in ("generatedAt", "auditRunId", "artifactLinks"):
        row.pop(field)
    core_rows.append(row)

matches = [
    row for row in core_rows
    if row["category"] == "DUPLICATE_FQCN_SOURCE_COLLISION"
    and row["symbol"] == "com.example.lms.strategy.RetrievalOrderService"
    and row["path"] == "main/java/com/example/lms/strategy/RetrievalOrderService.java"
]
assert len(matches) == 1
predecessor = matches[0]
assert predecessor["severity"] == "HIGH"
assert predecessor["fixEligibility"] == "REVIEW_REQUIRED"
assert predecessor["status"] == "HOLD"
assert predecessor["supersedes"] == []
assert predecessor["numericEvidence"] == {
    "duplicateFqcnGeneratedExcludeCount": 1,
    "duplicateFqcnHardExcludeCount": 0,
    "duplicateFqcnPackagedActiveCount": 0,
    "duplicateFqcnSourceCollisionCount": 1,
}
assert predecessor["baselineId"] == bundle.baseline["baselineId"]

duplicate_report_path = root / "app/build/desktop-reviewed-duplicate-wave3/reports/dup-fqcn-evidence.json"
duplicate_report_bytes = duplicate_report_path.read_bytes()
duplicate_report = audit._validate_duplicate_input(
    audit.load_required_input(
        duplicate_report_path,
        datetime.now(timezone.utc),
        require_freshness=False,
    )
)
collisions = [
    row for row in duplicate_report["collisions"]
    if row["fqcn"] == "com.example.lms.strategy.RetrievalOrderService"
]
assert len(collisions) == 1
collision = collisions[0]
assert collision == {
    "appPath": "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
    "evidenceFingerprint": collision["evidenceFingerprint"],
    "fqcn": "com.example.lms.strategy.RetrievalOrderService",
    "packagingState": "GENERATED_EXCLUDE",
    "rootPath": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
}
assert audit._is_lower_hex64(collision["evidenceFingerprint"])
assert duplicate_report["duplicateFqcnPackagedActiveCount"] == 0

target_paths = [
    "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
    "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
    "main/java/com/example/lms/strategy/RetrievalOrderService.java",
    "src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java",
    "src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java",
]
assert target_paths == sorted(target_paths, key=lambda value: (value.casefold(), value))
path_rows = {row["path"]: row for row in bundle.baseline["pathStateContentRows"]}
targets = []
for path in target_paths:
    row = path_rows[path]
    payload = (root / path).read_bytes()
    assert hashlib.sha256(payload).hexdigest() == row["contentSha256"]
    assert len(payload) == row["sizeBytes"]
    targets.append({
        "path": path,
        "existence": "FILE",
        "gitState": row["gitState"],
        "sizeBytes": row["sizeBytes"],
        "sha256": row["contentSha256"],
    })

duplicate_evidence = {
    "schemaVersion": "awx.structural-repair-duplicate-evidence.v1",
    "sourceIssueId": predecessor["issueId"],
    "rootCauseGroupId": predecessor["rootCauseGroupId"],
    "fqcn": "com.example.lms.strategy.RetrievalOrderService",
    "canonicalOwnerPath": target_paths[2],
    "compatibilityCopyPath": target_paths[0],
    "sourceCollisionCount": 1,
    "generatedExcludeCount": 1,
    "hardExcludeCount": 0,
    "packagedActiveCount": 0,
    "generatedExcludePattern": "com/example/lms/strategy/RetrievalOrderService*",
    "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
    "rawReportSha256": hashlib.sha256(duplicate_report_bytes).hexdigest(),
    "secretPatternHitCount": 0,
}
duplicate_evidence_bytes = audit.canonical_json_bytes(duplicate_evidence)

decision_material = {
    "schemaVersion": "awx.structural-repair-admission-decision.v1",
    "decisionType": "REVIEW_REQUIRED_DUPLICATE_OWNER",
    "sourceBaselineId": predecessor["baselineId"],
    "sourceIssueId": predecessor["issueId"],
    "rootCauseGroupId": predecessor["rootCauseGroupId"],
    "sourceEvidenceFingerprint": predecessor["evidenceFingerprint"],
    "sourceCategory": predecessor["category"],
    "sourceFixEligibility": predecessor["fixEligibility"],
    "sourceStatus": predecessor["status"],
    "sourceNumericEvidence": predecessor["numericEvidence"],
    "canonicalOwnerPath": target_paths[2],
    "proposedRepairTargetPath": target_paths[0],
    "repairTargetAdmissionRequired": True,
    "activeCallPath": target_paths[1],
    "behaviorTestPath": target_paths[4],
    "ownerContractTestPath": target_paths[3],
    "duplicateEvidenceSha256": hashlib.sha256(duplicate_evidence_bytes).hexdigest(),
    "approvedDesignSha256": design_sha,
    "ragControlExcluded": True,
}
decision = dict(decision_material)
decision["decisionId"] = hashlib.sha256(
    audit.canonical_json_bytes(decision_material)
).hexdigest()
decision_bytes = audit.canonical_json_bytes(decision)

target_preimages = {
    "schemaVersion": "awx.structural-repair-target-preimages.v1",
    "sourceHead": bundle.baseline["head"],
    "canonicalBranch": bundle.baseline["branch"],
    "isolatedBranch": bundle.baseline["branch"],
    "targets": targets,
}
target_bytes = audit.canonical_json_bytes(target_preimages)
predecessor_bytes = audit.canonical_json_bytes(predecessor)

summary = {
    "schemaVersion": "awx.structural-repair-intake.v2",
    "sourceBaselineId": predecessor["baselineId"],
    "sourceLedgerPayloadSha256": bundle.metrics["artifactLinks"]["ledgerPayloadSha256"],
    "sourceMetricsSemanticHash": bundle.metrics["semanticArtifactHash"],
    "sourceIssueIds": [predecessor["issueId"]],
    "rootCauseGroupIds": [predecessor["rootCauseGroupId"]],
    "evidenceFingerprints": [predecessor["evidenceFingerprint"]],
    "sourceBranch": bundle.baseline["branch"],
    "sourceHead": bundle.baseline["head"],
    "admissionMode": "EXPLICIT_REVIEW_PROMOTION",
    "admissionDecisionSha256": hashlib.sha256(decision_bytes).hexdigest(),
    "duplicateEvidenceSha256": hashlib.sha256(duplicate_evidence_bytes).hexdigest(),
}
summary_bytes = audit.canonical_json_bytes(summary)

files = {
    ".superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json": summary_bytes,
    ".superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl": predecessor_bytes,
    ".superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json": target_bytes,
    ".superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json": decision_bytes,
    ".superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json": duplicate_evidence_bytes,
}
for path, payload in files.items():
    value = audit._parse_canonical_json_bytes(payload, "closure-admission-invalid")
    audit._validate_public_strings(value)
    assert b"RagControlRuntimeAdapter" not in payload
    print(f"-----BEGIN {path} sha256={hashlib.sha256(payload).hexdigest()} bytes={len(payload)}-----")
    print(payload.decode("utf-8"), end="")
    print(f"-----END {path}-----")

print("STAGE_A=" + json.dumps({
    "baselineId": predecessor["baselineId"],
    "ledgerPayloadSha256": bundle.metrics["artifactLinks"]["ledgerPayloadSha256"],
    "metricsSemanticHash": bundle.metrics["semanticArtifactHash"],
    "issueId": predecessor["issueId"],
    "rootCauseGroupId": predecessor["rootCauseGroupId"],
    "evidenceFingerprint": predecessor["evidenceFingerprint"],
    "publicRawHashes": {
        "baseline": hashlib.sha256((root / audit.AUDIT_BASELINE_OUTPUT_RELATIVE).read_bytes()).hexdigest(),
        "ledger": hashlib.sha256((root / audit.AUDIT_LEDGER_OUTPUT_RELATIVE).read_bytes()).hexdigest(),
        "metrics": hashlib.sha256((root / audit.AUDIT_METRICS_OUTPUT_RELATIVE).read_bytes()).hexdigest(),
        "duplicateReport": hashlib.sha256(duplicate_report_bytes).hexdigest(),
    },
    "waveFileHashes": {
        path: hashlib.sha256(payload).hexdigest() for path, payload in files.items()
    },
}, sort_keys=True, separators=(",", ":")))
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'stage-a-capture-failed' }
```

Expected: exactly one canonical payload per declared nonempty wave file and one `STAGE_A=` record. Retain the hashes; do not persist raw build logs.

- [ ] **Step 4: Create the exact five nonempty files with `apply_patch`**

Use `apply_patch` only. Copy the exact one-line canonical JSON/NDJSON payloads printed in Step 3. Do not reformat, reorder keys, add a BOM, or add blank records. The final byte of every nonempty file must be LF.

- [ ] **Step 5: Create the two required zero-byte files with `apply_patch`**

Create exactly:

```text
.superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl
verification/structural-repair-waves/wave-0003/journal.jsonl
```

Both must have `Length=0`. Do not add a newline or placeholder row. Do not create any proof file.

- [ ] **Step 6: Directly validate the unregistered wave**

```powershell
@'
from pathlib import Path
import hashlib
import json
from scripts import dynamic_rag_quant_audit as audit

root = Path(".").resolve()
proof_root = (root / ".superpowers/sdd/structural-repair-waves/wave-0003").resolve()
summary_path = proof_root / "intake/intake-summary.json"
rows_path = proof_root / "intake/eligible-groups.jsonl"
targets_path = proof_root / "intake/target-preimages.json"
summary = json.loads(summary_path.read_text(encoding="utf-8"))
descriptor = audit.ClosureWaveDescriptor(
    wave_id="wave-0003",
    ordinal=3,
    journal_path=(root / "verification/structural-repair-waves/wave-0003/journal.jsonl").resolve(),
    proof_root=proof_root,
    source_baseline_id=summary["sourceBaselineId"],
    source_ledger_payload_sha256=summary["sourceLedgerPayloadSha256"],
    source_metrics_semantic_hash=summary["sourceMetricsSemanticHash"],
    intake_summary_sha256=hashlib.sha256(summary_path.read_bytes()).hexdigest(),
    eligible_groups_sha256=hashlib.sha256(rows_path.read_bytes()).hexdigest(),
    target_preimages_sha256=hashlib.sha256(targets_path.read_bytes()).hexdigest(),
)
history = audit.load_closure_wave(root=root, descriptor=descriptor)
assert len(history.predecessor_rows) == 1
assert history.all_events == ()
assert history.active_events == ()
assert history.proof_pairs == ()
assert history.target_admissions == ()
assert history.journal_payload_sha256 == hashlib.sha256(b"").hexdigest()
for suffix in (
    "repair-target-admission.json",
    "detector-red-summary.json",
    "compile-baseline.json",
    "owner-contract.json",
):
    assert not any(proof_root.glob(f"proofs/*/{suffix}"))
print("waveThreeDirectValidation=PASS")
print("descriptor=" + json.dumps({
    "waveId": descriptor.wave_id,
    "ordinal": descriptor.ordinal,
    "sourceBaselineId": descriptor.source_baseline_id,
    "sourceLedgerPayloadSha256": descriptor.source_ledger_payload_sha256,
    "sourceMetricsSemanticHash": descriptor.source_metrics_semantic_hash,
    "intakeSummarySha256": descriptor.intake_summary_sha256,
    "eligibleGroupsSha256": descriptor.eligible_groups_sha256,
    "targetPreimagesSha256": descriptor.target_preimages_sha256,
}, sort_keys=True, separators=(",", ":")))
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'wave-three-direct-validation-failed' }
```

Expected: `waveThreeDirectValidation=PASS` and a fully measured descriptor row.

- [ ] **Step 7: Enforce the no-intervening-producer and frozen-target boundary**

Rehash the three public artifacts and duplicate report and require exact equality with `STAGE_A.publicRawHashes`. Rehash all five frozen targets and compare to `target-preimages.json`. Rehash both RagControl files against Task 1. Confirm the Task-5 production/test hashes did not change. Do not run Gradle in this step.

- [ ] **Step 8: Record the Task-6 no-commit checkpoint**

Expected repository changes are only the producer-regenerated public artifacts and the seven new wave-3 files. Registry and Gradle remain at their Task-1 preimages; no Java/test/call-path/RagControl file changed; staged count remains `0`.

---

### Task 7: Registry Pin, Gradle Registration, And First Three-Wave Publication

**Files:**

- Modify: `scripts/dynamic_rag_quant_audit.py:304-309`
- Modify: `scripts/test_dynamic_rag_quant_audit.py:22-58,999-1278,2231-2350`
- Modify: `scripts/test_source_health_scorecard.py:51-78,1062-1158`
- Modify: `build.gradle.kts:398-503,566-587`
- Modify: `verification/structural-repair-waves/registry.json`
- Regenerate through producer: the three fixed public quantitative artifacts
- Regenerate through task: `verification/source-health-scorecard.json` and its existing sidecars
- Test: both Python modules and both public Gradle tasks

**Interfaces:**

- Consumes: the directly validated unregistered wave-3 descriptor from Task 6.
- Produces: a code-pinned canonical three-wave registry, exact Gradle inputs on both public tasks, and a freshly linked three-wave public bundle/source-health result.

- [ ] **Step 1: Update registry/Gradle tests first and verify RED**

Change the registry test to require three canonical descriptors in ordinal order and unchanged semantic objects for descriptors one and two. Extend the Gradle contract test to require:

```text
verification/structural-repair-waves/wave-0003/journal.jsonl
.superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json
.superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl
.superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json
.superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json
.superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json
.superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl
```

The six existing proof patterns must occur three times after registration. These four future patterns must occur exactly once and only in the wave-3 proof tree:

```text
proofs/*/repair-target-admission.json
proofs/*/detector-red-summary.json
proofs/*/compile-baseline.json
proofs/*/owner-contract.json
```

Both `dynamicRagQuantAudit` and `sourceHealthScorecard` must register the wave-3 journal, fixed inputs, and proof inputs with `PathSensitivity.RELATIVE`; neither task may use `inputs.dir` for the proof root.

Run:

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_closure_registry_accepts_exact_canonical_three_wave_descriptor_set `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_gradle_uses_only_the_fixed_closure_wave_registry_contract `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_empty_reviewed_duplicate_wave_three
```

Expected: RED because registry, pin, and Gradle still describe two waves. A missing-selector discovery error is not an acceptable RED; the edited tests must execute their assertions.

- [ ] **Step 2: Print the canonical three-wave registry without writing**

```powershell
@'
from pathlib import Path
import copy
import hashlib
import json
from scripts import dynamic_rag_quant_audit as audit

root = Path(".").resolve()
registry_path = root / audit.CLOSURE_WAVE_REGISTRY_RELATIVE
registry = audit._parse_canonical_json_bytes(
    registry_path.read_bytes(),
    "closure-registry-malformed",
)
assert len(registry["waves"]) == 2
first_two = copy.deepcopy(registry["waves"])
proof_root = root / ".superpowers/sdd/structural-repair-waves/wave-0003"
summary_path = proof_root / "intake/intake-summary.json"
rows_path = proof_root / "intake/eligible-groups.jsonl"
targets_path = proof_root / "intake/target-preimages.json"
summary = audit._parse_canonical_json_bytes(summary_path.read_bytes(), "closure-intake-mismatch")
third = {
    "waveId": "wave-0003",
    "ordinal": 3,
    "journalPath": "verification/structural-repair-waves/wave-0003/journal.jsonl",
    "proofRoot": ".superpowers/sdd/structural-repair-waves/wave-0003",
    "sourceBaselineId": summary["sourceBaselineId"],
    "sourceLedgerPayloadSha256": summary["sourceLedgerPayloadSha256"],
    "sourceMetricsSemanticHash": summary["sourceMetricsSemanticHash"],
    "intakeSummarySha256": hashlib.sha256(summary_path.read_bytes()).hexdigest(),
    "eligibleGroupsSha256": hashlib.sha256(rows_path.read_bytes()).hexdigest(),
    "targetPreimagesSha256": hashlib.sha256(targets_path.read_bytes()).hexdigest(),
}
payload = audit.canonical_json_bytes({
    "schemaVersion": "awx.structural-repair-wave-registry.v1",
    "waves": [*first_two, third],
})
print("registrySha256=" + hashlib.sha256(payload).hexdigest())
print(payload.decode("utf-8"), end="")
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'prospective-registry-build-failed' }
```

Retain the printed hash and exact one-line canonical registry payload.

- [ ] **Step 3: Apply the registry, code pin, and Gradle declarations as one scoped transaction**

Use `apply_patch` to:

1. replace `verification/structural-repair-waves/registry.json` with the exact canonical payload from Step 2;
2. replace `FROZEN_CLOSURE_WAVE_REGISTRY_SHA256` with the printed lowercase hash;
3. add `structuralRepairWaveThreeJournal`, `structuralRepairWaveThreeProofRoot`, `structuralRepairWaveThreeFixedInputs`, and `structuralRepairWaveThreeProofInputs` in `build.gradle.kts`;
4. register those exact inputs with relative sensitivity on both public tasks.

The Gradle declarations are:

```kotlin
val structuralRepairWaveThreeJournal =
    layout.projectDirectory.file("verification/structural-repair-waves/wave-0003/journal.jsonl")
val structuralRepairWaveThreeProofRoot =
    layout.projectDirectory.dir(".superpowers/sdd/structural-repair-waves/wave-0003")
val structuralRepairWaveThreeFixedInputs = files(
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl"
    ),
)
val structuralRepairWaveThreeProofInputs = fileTree(structuralRepairWaveThreeProofRoot) {
    include("proofs/*/red-summary.json")
    include("proofs/*/green-summary.json")
    include("proofs/*/event-baseline.json")
    include("proofs/*/gate-evidence.json")
    include("proofs/*/red-hold-summary.json")
    include("proofs/*/restored-control-summary.json")
    include("proofs/*/repair-target-admission.json")
    include("proofs/*/detector-red-summary.json")
    include("proofs/*/compile-baseline.json")
    include("proofs/*/owner-contract.json")
}
```

Do not add a runtime directory-discovery loop.

- [ ] **Step 4: Run registry, Gradle, v1 parity, and source-health fixture tests**

Rerun all three causal selectors from Step 1 together with the v1 parity, aggregate-history, CLI, and source-health fixture selectors:

```powershell
python -B -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_closure_registry_accepts_exact_canonical_three_wave_descriptor_set `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_gradle_uses_only_the_fixed_closure_wave_registry_contract `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_preserves_wave_one_predecessors_events_and_proofs `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_history_accepts_wave_one_wave_two_and_empty_reviewed_duplicate_wave_three `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_cli_requires_only_the_fixed_closure_wave_registry_argument `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_empty_reviewed_duplicate_wave_three `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_registry_backed_v2_history
```

Expected: all PASS.

- [ ] **Step 5: Validate registry and aggregate history directly**

```powershell
python -B -X utf8 -c "from pathlib import Path; from scripts.dynamic_rag_quant_audit import *; root=Path('.').resolve(); registry=load_closure_registry(root=root,registry_path=CLOSURE_WAVE_REGISTRY_RELATIVE); assert len(registry.waves)==3; history=load_closure_history(root=root,registry=registry); assert history.wave_count==3; assert history.event_count==14; assert history.rejected_false_positive_root_cause_groups==5; assert history.verified_closed_root_cause_groups==9; print(history.summary)"
```

Expected: direct history validation PASS with `3 / 14 / 5 / 9`.

- [ ] **Step 6: Regenerate the linked public bundle**

```powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave3ProjectCache dynamicRagQuantAudit
```

Expected: exit `0`; publication validates staged baseline, ledger, and metrics before replacement.

- [ ] **Step 7: Run source health through its declared dependency chain**

```powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave3ProjectCache sourceHealthScorecard
```

Expected: exit `0`. This command may rerun `dynamicRagQuantAudit`; final report hashes are captured only after it completes.

- [ ] **Step 8: Run both full Python modules against the linked three-wave public state**

```powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
```

Expected: both exit `0`. Running these modules before Steps 6 and 7 is prohibited because the just-pinned three-wave registry would otherwise be compared with stale two-wave public artifact links.

- [ ] **Step 9: Validate the final three-wave public bundle and exact counters**

```powershell
python -B -X utf8 -c "from pathlib import Path; from scripts.dynamic_rag_quant_audit import load_and_validate_current_bundle; from scripts.source_health_scorecard import validate_current_quant_bundle; root=Path('.').resolve(); bundle=load_and_validate_current_bundle(root=root); metrics=validate_current_quant_bundle(root); assert metrics==bundle.metrics; closure=metrics['closureHistorySummary']; ledger=metrics['ledgerSummary']; assert closure['waveCount']==3; assert closure['eventCount']==14; assert closure['rejectedFalsePositiveRootCauseGroups']==5; assert closure['verifiedClosedRootCauseGroups']==9; assert ledger['eligibleRootCauseGroups']==1; assert ledger['verifiedClosedRootCauseGroups']==9; print({'closure':closure,'eligible':ledger['eligibleRootCauseGroups'],'targetGap':ledger['targetGap']})"
```

Expected: all assertions PASS. The public `RetrievalOrderService` row remains `REVIEW_REQUIRED / HOLD`; no terminal row exists for it.

- [ ] **Step 10: Record the Task-7 no-commit checkpoint**

Hash the final registry, code pin, Gradle file, all seven wave files, three public artifacts, and source-health output. Verify both zero-byte files remain length 0, all four future target-admission proof patterns match zero files, staged count is 0, and all five candidate plus both RagControl hashes still equal their Task-6 frozen values.

---

### Task 8: Determinism, Privacy, Final Diff, And Handoff

**Files:**

- Read: every changed file and final public/private evidence artifact
- Regenerate through producer-dependent verification: public quantitative bundle and source-health outputs
- Modify: no production, test, registry, Gradle, intake, journal, progress, source, or proof file

**Interfaces:**

- Consumes: the fully registered and published three-wave state.
- Produces: two byte-independent normalized projections with one semantic hash, a final scoped manifest, and the execution handoff. It does not mark the parent goal complete.

- [ ] **Step 1: Define and capture normalized projection run 1**

Run this read-only projection after the last successful Task-7 producer-dependent command:

```powershell
@'
from pathlib import Path
import copy
import hashlib
import json
from datetime import datetime, timezone
from scripts import dynamic_rag_quant_audit as audit
from scripts import source_health_scorecard as score

root = Path(".").resolve()
bundle = audit.load_and_validate_current_bundle(root=root)
metrics = score.validate_current_quant_bundle(root)
assert metrics == bundle.metrics
core_rows = []
for envelope in bundle.ledger_rows:
    row = copy.deepcopy(envelope)
    for field in ("generatedAt", "auditRunId", "artifactLinks"):
        row.pop(field)
    core_rows.append(row)
duplicate_path = root / "app/build/desktop-reviewed-duplicate-wave3/reports/dup-fqcn-evidence.json"
duplicate = audit._validate_duplicate_input(
    audit.load_required_input(
        duplicate_path,
        datetime.now(timezone.utc),
        require_freshness=False,
    )
)
projection = {
    "auditRunId": bundle.metrics["auditRunId"],
    "baselineId": bundle.baseline["baselineId"],
    "ledgerCoreRows": core_rows,
    "artifactLinks": bundle.metrics["artifactLinks"],
    "semanticArtifactHash": bundle.metrics["semanticArtifactHash"],
    "closureHistorySummary": bundle.metrics["closureHistorySummary"],
    "ledgerSummary": bundle.metrics["ledgerSummary"],
    "duplicateSemanticHash": duplicate["semanticHash"],
    "sourceHealthBundleMatches": True,
}
payload = audit.canonical_json_bytes(projection)
print("projectionSha256=" + hashlib.sha256(payload).hexdigest())
print("projectionBytes=" + str(len(payload)))
'@ | python -B -X utf8 -
```

Retain the run-1 hash and byte count.

- [ ] **Step 2: Run the final producer-dependent chain a second time**

```powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave3ProjectCache sourceHealthScorecard
```

Expected: exit `0`.

- [ ] **Step 3: Capture normalized projection run 2 and compare**

Run the exact Step-1 script again. Require identical `projectionSha256` and `projectionBytes`. A mismatch is `semantic-nondeterminism`; do not select the newer output by timestamp.

- [ ] **Step 4: Run the final focused and full verification ladder**

```powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
python -B -X utf8 -c "from pathlib import Path; from scripts.dynamic_rag_quant_audit import load_and_validate_current_bundle; from scripts.source_health_scorecard import validate_current_quant_bundle; root=Path('.').resolve(); bundle=load_and_validate_current_bundle(root=root); assert validate_current_quant_bundle(root)==bundle.metrics; print('finalValidators=PASS')"
```

Expected: both full modules and both validators PASS.

- [ ] **Step 5: Verify canonical bytes, zero-byte files, and privacy**

For every nonempty wave-3 JSON/NDJSON file, round-trip through the production canonical parser and require raw-byte equality. Require progress and journal length `0`. Scan only the changed targets and new evidence artifacts with the repository `SECRET_PATTERN`; report counts only. Require zero Windows absolute-path hits in public/private JSON. Do not print matching content.

- [ ] **Step 6: Verify v1 preservation and no live repair admission**

Recompute wave-1 and wave-2 intake/progress/journal/proof hashes from Task 1 and require exact equality. Require all fourteen pre-existing event IDs still present, five active rejected groups, nine active verified groups, no wave-3 event, no wave-3 progress row, and no file matching the four future target-admission patterns.

- [ ] **Step 7: Verify source and HOLD immutability**

Recompute all five target hashes from Task 6 and both RagControl hashes from Task 1. Require exact equality. Inspect `git diff --` for all five targets and both RagControl paths; there must be no hunk introduced by this execution.

- [ ] **Step 8: Inspect the final scoped diff**

```powershell
git diff --check
git status --short
git diff --stat -- `
  scripts/dynamic_rag_quant_audit.py `
  scripts/test_dynamic_rag_quant_audit.py `
  scripts/test_source_health_scorecard.py `
  build.gradle.kts `
  verification/structural-repair-waves/registry.json
```

Manually inspect every changed hunk plus all seven new wave files. Confirm `scripts/source_health_scorecard.py` and all Java files are unchanged by this plan.

- [ ] **Step 9: Produce the final execution handoff without committing**

Report:

- exact changed/created paths;
- approved spec hash;
- final registry hash and code-pin equality;
- Stage-A baseline/ledger/metrics/report hashes;
- seven wave-file hashes and both zero-byte lengths;
- final public artifact hashes;
- wave/event/rejected/verified/eligible/target-gap counters;
- full Python test counts and Gradle task outcomes;
- two normalized projection hashes and byte counts;
- secret/absolute-path hit counts;
- v1 preservation result;
- candidate and RagControl immutability result;
- staged/index-lock/PatchDrop/lease counts;
- `repairCreditAdded=0`;
- `parentGoalComplete=false`;
- one next action: write and approve a separate bounded source-repair design before any repair-target admission or Java mutation.

Do not stage, commit, push, or mark the approximately-1,000 structural-repair goal complete.

## Plan Self-Review Checklist

- Spec objective and scope: Tasks 1, 5, 6, 7, and 8 preserve the no-Java, no-RagControl, no-credit boundary.
- Intake-v2 schema and exact reviewed predecessor: Task 2.
- Proposed target versus independently admitted target: Task 3; live admission files remain absent in Tasks 6-8.
- Operational detector `DISAPPEARED`: Task 3 fixture contract; no live source claim.
- Strict v1/v2 per-wave dispatch: Tasks 2 and 3.
- Aggregate history and source-health delegation: Task 4.
- Final-producer Stage-A ordering: Tasks 5 and 6.
- Wave freeze and direct validation before registry mutation: Task 6.
- Registry pin and exact Gradle inputs: Task 7.
- Atomic public publication, rollback, determinism, privacy, and final counters: Tasks 4, 7, and 8.
- Wave-1/wave-2 byte preservation: Tasks 1, 2, 4, and 8.
- No placeholder values are used for future hashes; Tasks 6 and 7 give exact read-only construction commands that measure and print canonical bytes before `apply_patch`.
- Function and type names are consistent across tasks: `ClosureWaveIntake`, `ClosureTargetAdmission`, `ClosureProgressEntry`, strict `_load_closure_intake`, `_load_progress_rows`, and existing public loaders.
