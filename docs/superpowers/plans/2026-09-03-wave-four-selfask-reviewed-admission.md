# Wave-4 SelfAsk Exact Reviewed-Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Register exactly one freshly reproduced SelfAsk duplicate-ownership predecessor in an empty, hash-pinned Wave 4 without changing either SelfAsk source, touching RagControlRuntimeAdapter, creating repair evidence, or awarding closure credit.

**Architecture:** scripts/dynamic_rag_quant_audit.py remains the sole schema, registry, intake, history, overlay, and public-bundle truth owner. Existing intake v1 and the exact RetrievalOrderService intake-v2 branch remain unchanged. A sibling intake-v3 branch accepts only wave-0004 ordinal 4 and the exact service.rag.planner.SelfAskPlanner reviewed-admission contract. Infrastructure becomes green while the live registry still has three descriptors; one final isolated producer invocation then supplies the fresh Stage-A ledger row, detailed duplicate row, zero-caller observation, and five target preimages. Only after the unregistered empty wave validates are the registry pin and exact Gradle inputs updated and the linked public bundle republished.

**Tech Stack:** Python 3 standard library and unittest; Kotlin Gradle DSL; Gradle Wrapper; canonical UTF-8 JSON and NDJSON; SHA-256; PowerShell on Windows; Java 17.

**Spec:** docs/superpowers/specs/2026-09-03-wave-four-selfask-reviewed-admission-design.md, approved byte identity 1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded.

## Global Constraints

- Recompute the spec hash before Task 1 and require exactly 1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded.
- Bind the raw plan SHA-256 supplied in the execution handoff. This plan cannot embed its own hash because doing so would change that hash.
- The live pre-registration registry remains the exact canonical three-wave file with planning-time SHA-256 e1791b53a62d7dd1f24e6c4598b1652c353c9c4c86c22e6994b0dd895c14db4b until Task 7.
- Do not modify, delete, rename, or move either SelfAsk source file, app/build.gradle.kts, main/java/config/RagLightAdapters.java, or any RagControlRuntimeAdapter source or test.
- Do not create src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java. Its absence is one of the five target preimages.
- Do not use AppCleanSourceZombieContractTest as SelfAsk proof. Its asserted com/example/lms path is not the selected service/rag path.
- Do not run the application-source three-way preflight in this registration plan because no application-source mutation is authorized. The separately approved future repair must run demo1-source-edit-three-way-preflight before any application-source edit.
- Do not globally reclassify DUPLICATE_FQCN_SOURCE_COLLISION. SelfAsk remains REVIEW_REQUIRED / HOLD before and after registration.
- proposedRepairTargetPath is an evidence-bound proposal only; it grants no edit, move, or deletion authority.
- If SelfAsk fails row, duplicate, caller, cleanliness, or preimage admission, stop Wave 4. Do not substitute ONNX, AnswerSanitizer, RagControlRuntimeAdapter, or another candidate.
- Intake v1 and the exact wave-0003 RetrievalOrderService intake-v2 branch retain their existing schemas, field sets, identities, reason ordering, progress/event behavior, and bytes.
- Intake v3 is accepted only for wave-0004, ordinal 4, admissionMode=EXACT_SELFASK_REVIEW_PROMOTION, one exact SelfAsk predecessor, five exact target-preimage rows, one admission-decision-v2 object, and one duplicate-evidence-v2 object.
- The final Stage-A caller probe is operational evidence used to create duplicate-evidence. Historical intake loading validates the frozen count and hash; it must not rescan current Java or reinterpret the past after a future repair.
- Wave-4 repair-progress.jsonl and journal.jsonl are exactly zero bytes. The Wave-4 proofs namespace is absent or empty. Any progress row, journal event, proof entry, or repair-target admission fails closed.
- Admission and registration add zero VERIFIED_CLOSED credit. Assuming no independently explained detector drift, the expected final state is four waves, seventeen predecessors, fifteen events, five rejected groups, ten verified groups, one OPEN / ELIGIBLE group, and target gap 889.
- RagControlRuntimeAdapter remains the one OPEN / ELIGIBLE group and remains user-held. Its source, test, ledger/history contract, and status are outside this plan.
- Use Java 17, AWX_SPLIT_BUILD_OUTPUTS=1, host ID desktop-wave4-selfask, one isolated GRADLE_USER_HOME, and one isolated Gradle project cache for every Gradle command in Tasks 6 through 8.
- Run the final pre-registration dynamicRagQuantAudit producer exactly once in Task 6. Do not run sourceHealthScorecard or another producer-dependent Gradle task between that invocation and completion of the Wave-4 freeze.
- Publication order remains baseline, ledger, then metrics through the existing transaction. Do not add a Wave-4 publisher.
- Do not run Browser, Computer, Supabase, provider, database, production, deployment, credential, destructive, cleanup, commit, stage, or push operations.
- Commit authority has not been granted. Replace normal Superpowers commit steps with hash, status, and diff checkpoints.
- Preserve every unrelated dirty or untracked path and every user hunk. Do not use reset, checkout, clean, stash, or broad formatting.
- Use apply_patch for repository edits. Generated public artifacts continue to be written only by the existing Gradle producer.
- Run Python with -B -X utf8 and PYTHONDONTWRITEBYTECODE=1.
- At the first true gate failure, stop the affected lane and record holdScope, firstBlockingRule, blockingEvidence, independentWorkCompleted, and repositoryWideHold=false unless no authorized lane remains safe.

## File Structure And Ownership

- Modify scripts/dynamic_rag_quant_audit.py in Tasks 2, 3, and 7: distinct v3 constants, the exact SelfAsk validator, the Stage-A-only caller counter, empty Wave-4 progress/journal/proof handling, and the final registry pin.
- Modify scripts/test_dynamic_rag_quant_audit.py in Tasks 2, 3, and 7: exact v3 fixtures, causal negative tests, aggregate and rollback tests, live four-descriptor registry assertions, and Gradle input assertions.
- Modify scripts/test_source_health_scorecard.py in Tasks 4 and 7: arbitrary registered-wave fixture seeding, explicit Wave-4 installation, shared-loader parity, tamper rejection, and the live four-wave assertion.
- Keep scripts/source_health_scorecard.py unchanged unless an exact Task-4 delegation RED proves the existing shared loader cannot consume a valid fourth descriptor. A parallel v3 classifier is forbidden.
- Modify build.gradle.kts only in Task 7: declare the exact Wave-4 journal and six fixed private inputs on dynamicRagQuantAudit and sourceHealthScorecard with PathSensitivity.RELATIVE.
- Modify verification/structural-repair-waves/registry.json only in Task 7: append one ordinal-4 descriptor to the first three unchanged semantic descriptor objects.
- Create .superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json in Task 6.
- Create .superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl in Task 6.
- Create .superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json in Task 6.
- Create .superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json in Task 6.
- Create .superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json in Task 6.
- Create .superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl in Task 6 as exactly zero bytes.
- Create verification/structural-repair-waves/wave-0004/journal.jsonl in Task 6 as exactly zero bytes.
- Do not create any file under .superpowers/sdd/structural-repair-waves/wave-0004/proofs.
- Do not modify any wave-1, wave-2, or wave-3 intake, progress, proof, or journal file.

---

### Task 1: Immutable Preflight And Approved-Boundary Checkpoint

**Files:**

- Read: docs/superpowers/specs/2026-09-03-wave-four-selfask-reviewed-admission-design.md
- Read: docs/superpowers/plans/2026-09-03-wave-four-selfask-reviewed-admission.md
- Read: scripts/dynamic_rag_quant_audit.py
- Read: scripts/harmony_catch_contract.py
- Read: scripts/test_dynamic_rag_quant_audit.py
- Read: scripts/source_health_scorecard.py
- Read: scripts/test_source_health_scorecard.py
- Read: build.gradle.kts and app/build.gradle.kts
- Read: verification/structural-repair-waves/registry.json
- Read: all registered wave inputs and current public quantitative artifacts
- Read: the four existing SelfAsk/guard targets, the absent future-test path, and both RagControl HOLD paths
- Modify: none

**Interfaces:**

- Consumes the approved spec, execution-handoff plan hash, current three-wave registry, current internally linked public bundle, and live Git/ownership state.
- Produces a task-local count/hash-only evidence packet. It is terminal or temporary-cache evidence, not a repository artifact.

- [ ] **Step 1: Verify repository, Java, branch, worktree, lock, pending-patch, and lease gates**

Run from C:\AbandonWare\demo-1\demo-1\src:

~~~powershell
$repoRoot = (Resolve-Path -LiteralPath '.').Path
$branch = git branch --show-current
$head = git rev-parse HEAD
$stagedCount = @(git diff --cached --name-only).Count
$indexLock = Test-Path -LiteralPath '.git\index.lock'
$topLevelPatchCount = @(Get-ChildItem -LiteralPath '__patch_drop__' -File -Filter '*.patch' -ErrorAction SilentlyContinue).Count
$activeLeaseCount = @(Get-ChildItem -LiteralPath '__patch_drop__\source-edit-locks' -Directory -ErrorAction SilentlyContinue).Count
$worktrees = @(git worktree list --porcelain)
$javaVersion = (& java -version 2>&1 | Select-Object -First 1)
[pscustomobject]@{repoRoot=$repoRoot;branch=$branch;head=$head;stagedCount=$stagedCount;indexLock=$indexLock;topLevelPatchCount=$topLevelPatchCount;activeLeaseCount=$activeLeaseCount;javaVersion=[string]$javaVersion;worktreeLineCount=$worktrees.Count} | Format-List
~~~

Expected: the root is the named Desktop checkout; Java reports version 17; branch is codex/owned-runtime-browser-restart; HEAD is 0796a3c5b29bbb08c3314bd40649d856d4a7bce6 unless a separately explained user change preceded execution; staged count is 0; index lock is false; top-level patch count and active source-edit lease count are 0. An overlapping writer or changed branch/HEAD is a Wave-4 lane HOLD, not permission to clean the tree.

- [ ] **Step 2: Verify approved spec and bind the externally supplied plan identity**

~~~powershell
$specPath = 'docs/superpowers/specs/2026-09-03-wave-four-selfask-reviewed-admission-design.md'
$planPath = 'docs/superpowers/plans/2026-09-03-wave-four-selfask-reviewed-admission.md'
$specHash = (Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant()
$planHash = (Get-FileHash -LiteralPath $planPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($specHash -ne '1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded') { throw 'approved-spec-drift' }
if ($planHash -ne $env:AWX_APPROVED_WAVE4_PLAN_SHA256) { throw 'approved-plan-drift' }
[pscustomobject]@{specSha256=$specHash;planSha256=$planHash} | Format-List
~~~

Before running, set AWX_APPROVED_WAVE4_PLAN_SHA256 in the current process to the exact hash from the user-approved execution handoff. Do not persist it. An absent or mismatched value stops before any edit.

- [ ] **Step 3: Verify planning-time production, registry, public, candidate, and HOLD preimages**

~~~powershell
$expected = [ordered]@{
    'scripts/dynamic_rag_quant_audit.py' = '8e6b724a546b2e6eadf2f832c656d0d68217042526c06e043d57577a4d30e35d'
    'scripts/test_dynamic_rag_quant_audit.py' = 'c4b1265deb100c7af5b67728eba74bbd0321b3b50c96e13d26608606490a6332'
    'scripts/source_health_scorecard.py' = 'c954dd65386e921f524fd989d2b95da0cdcf0a765c10e158b4c4bc2be9d90c7f'
    'scripts/test_source_health_scorecard.py' = '3aeda27176573708f1b7407bb7b8302cb06793810650d9ebc8eb6d968b4794ff'
    'build.gradle.kts' = 'ef3828a9ae24c25f4f6fcb2ac66f5fbccf41d5a6ef18638bebdab88db52acfc1'
    'app/build.gradle.kts' = 'b1590e63596000c944d6a1e94bf325323d001d41ba8cc9360e3462f1dd4c4597'
    'verification/structural-repair-waves/registry.json' = 'e1791b53a62d7dd1f24e6c4598b1652c353c9c4c86c22e6994b0dd895c14db4b'
    'verification/structural-design-baseline.json' = 'c377bf10d12f9c2e68e3ff6187c75a9b3dea748db49fee844ff5b246d55f631d'
    'verification/structural-design-debt-ledger.jsonl' = 'c37a6d026c80dc3701589071a5f35b3d2d5976483479a111a7f6d9bec81d7729'
    'verification/dynamic-rag-quant-audit-metrics.json' = 'e8ef3f56a8f6130950d2e38edd4383abcc7f3aa6a3340e8c7888b4d59f5721bb'
    'verification/source-health-scorecard.json' = '43db643bbba312a569630af5dfac7f76461515339a2b86081e6574b23ebfe9ad'
    'verification/dynamic-rag-harmony-pressure-metrics.json' = '6690acd479174a884e75451045ec58bd1f6c4bdba3fd63ed901ba88f1f3570a4'
    'verification/test-tree-contamination-metrics.json' = 'a7369559c24a928183fcd024631bed769852f14913d8ec65bf99c4b4a2dafc3e'
    'app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java' = '98598256965d3ff9978c763d3639a8d9017062c6a5a7c820d803adbaa1d16b64'
    'main/java/config/RagLightAdapters.java' = '9974081bff3e38182efc40cf659176cdc6bf62022496f3ed8cbafd896becfc95'
    'main/java/service/rag/planner/SelfAskPlanner.java' = '7c1a82407fa741d5aa7ed5509ddd9d6779a00055f79eccab674af52eb8834786'
    'main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java' = 'd77468cab11c2e7be80c17495d938a05247447f8097c3899464a55101089001e'
    'src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java' = 'd66bffda959049e8e5b7cb3d2890459c942a02c51b4d4f1aedd7e3dc733e6cfe'
}
$rows = foreach ($entry in $expected.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Key -PathType Leaf)) { throw "required-preimage-missing:$($entry.Key)" }
    $actual = (Get-FileHash -LiteralPath $entry.Key -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $entry.Value) { throw "planning-preimage-drift:$($entry.Key)" }
    [pscustomobject]@{path=$entry.Key;sizeBytes=(Get-Item -LiteralPath $entry.Key).Length;sha256=$actual;status=@(git status --short -- $entry.Key) -join ''}
}
$rows | Format-Table -AutoSize
~~~

The exact planning-time values are a start-boundary guard. If a legitimate user change altered one of these files before execution, stop this plan and revise the approved preimages rather than silently adopting it.

- [ ] **Step 4: Verify the future test and every Wave-4 path are absent**

~~~powershell
$absent = @(
    'src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java',
    '.superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json',
    '.superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl',
    '.superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json',
    '.superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json',
    '.superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json',
    '.superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl',
    'verification/structural-repair-waves/wave-0004/journal.jsonl'
)
foreach ($path in $absent) {
    $candidate = Join-Path (Get-Location) $path
    if ([System.IO.File]::Exists($candidate) -or [System.IO.Directory]::Exists($candidate)) { throw "unexpected-wave4-preimage:$path" }
}
'waveFourPathsAbsent=PASS'
~~~

Expected: all eight paths are absent. A stale partial Wave-4 file is not reused.

- [ ] **Step 5: Confirm source-set ownership and the pure lexer seam**

~~~powershell
rg -n 'srcDirs|main/java|app/src/main/java_clean|app/src/main/java' build.gradle.kts app/build.gradle.kts
rg -n 'def strip_java_comments_and_strings_preserve_lines' scripts/harmony_catch_contract.py
~~~

Expected: root main/java and app/src/main/java_clean remain active; app/src/main/java remains inactive; the lexer function has signature strip_java_comments_and_strings_preserve_lines(text: str) -> str.

- [ ] **Step 6: Validate the current three-wave bundle through both public validators**

~~~powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
python -B -X utf8 -c "from pathlib import Path; from scripts import dynamic_rag_quant_audit as a; from scripts import source_health_scorecard as s; root=Path('.').resolve(); bundle=a.load_and_validate_current_bundle(root=root); metrics=s.validate_current_quant_bundle(root); assert metrics==bundle.metrics; history=metrics['closureHistorySummary']; ledger=metrics['ledgerSummary']; assert history['waveCount']==3; assert history['eventCount']==15; assert history['rejectedFalsePositiveRootCauseGroups']==5; assert history['verifiedClosedRootCauseGroups']==10; assert ledger['eligibleRootCauseGroups']==1; assert ledger['verifiedClosedRootCauseGroups']==10; assert ledger['targetGap']==889; print({'currentBundle':'PASS','history':history,'eligible':ledger['eligibleRootCauseGroups'],'targetGap':ledger['targetGap']})"
~~~

Expected: both validators agree and the exact counters pass. This proves internal current-bundle coherence; the final Stage-A producer remains mandatory.

- [ ] **Step 7: Capture registered-wave preservation hashes and the no-write checkpoint**

Hash every file named by the three current registry descriptors, including referenced proof files, and retain the sorted path/size/hash manifest outside the repository or in terminal evidence. Recheck staged count, index lock, top-level PatchDrop count, active leases, the five SelfAsk/guard target states, and both RagControl hashes. Do not edit, stage, or commit.

---

### Task 2: Strict Intake-V3 SelfAsk Admission Loader And Stage-A Caller Probe

**Files:**

- Modify: scripts/dynamic_rag_quant_audit.py
- Modify: scripts/test_dynamic_rag_quant_audit.py
- Read only: scripts/harmony_catch_contract.py
- Test: scripts/test_dynamic_rag_quant_audit.py

**Interfaces:**

- Adds CLOSURE_INTAKE_V3_SCHEMA, CLOSURE_ADMISSION_DECISION_V2_SCHEMA, CLOSURE_DUPLICATE_EVIDENCE_V2_SCHEMA, WAVE_FOUR_APPROVED_DESIGN_SHA256, and distinct exact-field sets.
- Adds _count_selfask_compatibility_copy_direct_callers(*, root: Path, compatibility_copy_path: str) -> int for Task-6 Stage-A use only.
- Adds the keyword-only _validate_selfask_reviewed_admission_intake_v3 function returning ClosureWaveIntake.
- Keeps ClosureWaveIntake unchanged and does not alter the v1/v2 validator.

- [ ] **Step 1: Add fixture helpers and causal v3 tests before production code**

Add these helpers beside the existing reviewed-duplicate v2 helpers:

- write_selfask_reviewed_admission_intake_v3 accepts FixturePaths, predecessor bundle, predecessor core row, duplicate-report SHA-256, raw collision fingerprint, target-row list, and caller-file count; it returns the admission-decision object.
- prepare_selfask_reviewed_admission_intake_v3 accepts FixturePaths and an optional fixed datetime; it returns predecessor bundle, Wave-4 descriptor, predecessor core row, and admission decision.
- install_empty_selfask_reviewed_admission_wave_four accepts FixturePaths and an optional fixed datetime; it returns the canonical registry SHA-256 and Wave-4 descriptor.

The writer uses canonical_bytes for every nonempty file and these exact evidence fields:

~~~python
evidence = {
    "schemaVersion": "awx.structural-repair-duplicate-evidence.v2",
    "sourceIssueId": predecessor_row["issueId"],
    "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
    "fqcn": "service.rag.planner.SelfAskPlanner",
    "canonicalOwnerPath": "main/java/service/rag/planner/SelfAskPlanner.java",
    "compatibilityCopyPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
    "packagingState": "GENERATED_EXCLUDE",
    "sourceCollisionCount": 1,
    "generatedExcludeCount": 1,
    "hardExcludeCount": 0,
    "packagedActiveCount": 0,
    "appActiveSourceRoot": "app/src/main/java_clean",
    "compatibilityCopyDirectCallerFileCount": caller_file_count,
    "callerProbeToken": "java-active-app-direct-caller-files-v1",
    "generatedExcludePattern": "service/rag/planner/SelfAskPlanner*",
    "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
    "rawReportSha256": duplicate_report_sha256,
    "rawCollisionEvidenceFingerprint": raw_collision_evidence_fingerprint,
    "secretPatternHitCount": 0,
}
~~~

The admission decision uses this exact shape, then sets decisionId to the SHA-256 of canonical bytes with decisionId removed:

~~~python
decision = {
    "schemaVersion": "awx.structural-repair-admission-decision.v2",
    "decisionType": "EXACT_SELFASK_REVIEWED_DUPLICATE_OWNER",
    "sourceBaselineId": predecessor_row["baselineId"],
    "sourceIssueId": predecessor_row["issueId"],
    "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
    "sourceEvidenceFingerprint": predecessor_row["evidenceFingerprint"],
    "sourceCategory": predecessor_row["category"],
    "sourceFixEligibility": predecessor_row["fixEligibility"],
    "sourceStatus": predecessor_row["status"],
    "sourceNumericEvidence": predecessor_row["numericEvidence"],
    "candidateFqcn": "service.rag.planner.SelfAskPlanner",
    "canonicalOwnerPath": "main/java/service/rag/planner/SelfAskPlanner.java",
    "proposedRepairTargetPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
    "repairTargetAdmissionRequired": True,
    "packagingConfigPath": "app/build.gradle.kts",
    "activeCallPath": "main/java/config/RagLightAdapters.java",
    "futureOwnershipTestPath": "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java",
    "duplicateEvidenceSha256": sha256_hex(canonical_bytes(evidence)),
    "approvedDesignSha256": "1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded",
    "ragControlExcluded": True,
}
decision["decisionId"] = identity_hash(decision, "decisionId")
~~~

The intake summary uses schema awx.structural-repair-intake.v3, admissionMode EXACT_SELFASK_REVIEW_PROMOTION, and transitive hashes of decision/evidence. The five target rows are sorted exactly as:

~~~text
app/build.gradle.kts
app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java
main/java/config/RagLightAdapters.java
main/java/service/rag/planner/SelfAskPlanner.java
src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java
~~~

The first four fixture targets are FILE rows. The compatibility copy is clean. The fifth is exactly ABSENT / absent / 0 / sixty-four zeroes.

Add these tests:

- test_wave_four_v3_intake_accepts_exact_selfask_reviewed_predecessor
- test_wave_four_v3_intake_rejects_wrong_wave_candidate_or_stage_a_identity
- test_wave_four_v3_intake_rejects_nonzero_caller_probe_or_dirty_compatibility_copy
- test_wave_four_v3_intake_rejects_wrong_five_target_preimages_or_decision_links
- test_wave_four_v3_intake_rejects_noncanonical_unbound_or_swapped_duplicate_evidence
- test_wave_four_v3_rejects_v1_v2_and_unknown_schema_substitutions
- test_wave_three_v2_rejects_wave_four_v3_fields_without_changing_v2_contract
- test_wave_four_v3_loader_uses_frozen_caller_evidence_without_rescanning_live_tree
- test_selfask_caller_probe_excludes_declaration_and_strips_comments_strings
- test_selfask_caller_probe_counts_identifier_in_another_active_app_java_file
- test_selfask_caller_probe_rejects_alias_reparse_or_missing_active_root

The historical-loader test mutates fixture Java after intake creation and still loads the same frozen evidence. It proves only Task 6 invokes the live caller helper.

- [ ] **Step 2: Run the exact causal RED command**

~~~powershell
$selectors = @(
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_intake_accepts_exact_selfask_reviewed_predecessor',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_intake_rejects_wrong_wave_candidate_or_stage_a_identity',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_intake_rejects_nonzero_caller_probe_or_dirty_compatibility_copy',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_intake_rejects_wrong_five_target_preimages_or_decision_links',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_intake_rejects_noncanonical_unbound_or_swapped_duplicate_evidence',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_rejects_v1_v2_and_unknown_schema_substitutions',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_rejects_wave_four_v3_fields_without_changing_v2_contract',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_loader_uses_frozen_caller_evidence_without_rescanning_live_tree',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_selfask_caller_probe_excludes_declaration_and_strips_comments_strings',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_selfask_caller_probe_counts_identifier_in_another_active_app_java_file',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_selfask_caller_probe_rejects_alias_reparse_or_missing_active_root'
)
python -B -X utf8 -m unittest @selectors
~~~

Expected: tests execute and fail because the v3 constants, helper, dispatch, and validator do not exist. Import or discovery failure is not causal RED; correct test syntax until assertions execute.

- [ ] **Step 3: Add the one-way pure lexer import and distinct v3 constants**

At the imports:

~~~python
try:
    from .harmony_catch_contract import (
        strip_java_comments_and_strings_preserve_lines,
    )
except ImportError:
    from harmony_catch_contract import (
        strip_java_comments_and_strings_preserve_lines,
    )
~~~

Add:

~~~python
CLOSURE_INTAKE_V3_SCHEMA = "awx.structural-repair-intake.v3"
CLOSURE_ADMISSION_DECISION_V2_SCHEMA = "awx.structural-repair-admission-decision.v2"
CLOSURE_DUPLICATE_EVIDENCE_V2_SCHEMA = "awx.structural-repair-duplicate-evidence.v2"
WAVE_FOUR_APPROVED_DESIGN_SHA256 = (
    "1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded"
)
~~~

Add CLOSURE_INTAKE_V3_SUMMARY_FIELDS with the same keys as v2 but a separate symbol. Add exact CLOSURE_ADMISSION_DECISION_V2_FIELDS and CLOSURE_DUPLICATE_EVIDENCE_V2_FIELDS matching the approved spec. Do not change the three existing v2 symbols or field sets.

- [ ] **Step 4: Implement the Stage-A-only caller counter**

Implement the exact signature _count_selfask_compatibility_copy_direct_callers(*, root: Path, compatibility_copy_path: str) -> int.

Required behavior:

1. Resolve root strictly and resolve app/src/main/java_clean through _safe_resolved_child.
2. Require compatibility_copy_path to equal app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java.
3. Require the declaration to be a contained regular file without a symlink/reparse component.
4. Enumerate only regular Java files under app/src/main/java_clean without following links or reparse directories.
5. Sort canonical repository-relative paths by casefold then original value.
6. Exclude the declaration.
7. Decode UTF-8 strictly and map I/O, decode, alias, or reparse failures to closure-admission-invalid.
8. Strip comments and string/character literals with strip_java_comments_and_strings_preserve_lines.
9. Count files, not occurrences, when stripped content matches the identifier token SelfAskPlanner.
10. Return only a nonnegative count; emit no file list or source snippet.

This helper is not called from any persistent intake/history/public-bundle loader.

- [ ] **Step 5: Add strict v3 dispatch and the exact SelfAsk validator**

In _load_closure_intake, add a v3 branch before the v1 fallback and pin it to wave-0004 ordinal 4. Delegate to _validate_selfask_reviewed_admission_intake_v3 with keyword-only proof_root: Path, descriptor: ClosureWaveDescriptor, summary: dict[str, Any], predecessor_rows as the canonical tuple of ledger-core dictionaries, and target_value: dict[str, Any]; it returns ClosureWaveIntake.

Validate in order:

1. Exact v3 summary fields, descriptor identity, admission mode, one predecessor, branch/HEAD, and target header.
2. _validate_ledger_core_rows and exact predecessor category/severity/eligibility/status/supersedes/symbol/path/numeric evidence.
3. Summary issue/group/fingerprint lists equal the row.
4. Exactly five canonical target rows in the approved order, no alias/collision/reparse; app copy FILE/clean; future test ABSENT/absent/0/zero hash; the other three FILE with valid recorded states and hashes.
5. Canonical decision/evidence bytes, exact field sets/schemas, raw hashes, and summary transitive hashes.
6. Decision identity hash, Stage-A source identity, exact SelfAsk paths and enums, approved design hash, duplicate evidence hash, and ragControlExcluded=true.
7. Evidence identity, exact pair/state/counts/root/probe/pattern/command, caller count zero, raw hashes, and secret count zero. Require rawCollisionEvidenceFingerprint to differ from the predecessor ledger evidenceFingerprint so the two approved evidence identities cannot be substituted.
8. Return a ClosureWaveIntake with schema_version=CLOSURE_INTAKE_V3_SCHEMA and frozen parsed values.

Use closure-intake-mismatch for descriptor/summary/ledger/header failure and closure-admission-invalid for candidate/evidence/decision/target/caller/transitive-hash failure. The validator never invokes the live caller probe.

- [ ] **Step 6: Run causal GREEN and v1/v2 parity controls**

Run the Step-2 selectors. Then:

~~~powershell
$parity = @(
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_accepts_one_exact_reviewed_duplicate_predecessor',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_rejects_wrong_descriptor_row_decision_and_evidence',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_rejects_ragcontrol_identity_or_target',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_rejects_malformed_absent_target_preimage',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_rejects_raw_hash_mismatch_and_noncanonical_admission_bytes',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_preserves_wave_one_predecessors_events_and_proofs'
)
python -B -X utf8 -m unittest @parity
~~~

Expected: all pass. No Gradle task, registry edit, Wave-4 file, or public publication occurs.

- [ ] **Step 7: Inspect Task-2 diff and record hashes**

Run git diff --check and inspect only the two owned files. Verify scripts/harmony_catch_contract.py, v2 constants/field sets/validator, registry, Gradle, public artifacts, candidates, future-test absence, and RagControl are unchanged.

---

### Task 3: Empty Wave-4 History, Four-Wave Aggregation, And Rollback Contracts

**Files:**

- Modify: scripts/dynamic_rag_quant_audit.py
- Modify: scripts/test_dynamic_rag_quant_audit.py
- Test: scripts/test_dynamic_rag_quant_audit.py

**Interfaces:**

- Extends _load_progress_rows only for the v3 empty-history case.
- Extends _load_closure_wave_from_journal_bytes so nonempty v3 journals fail before event dispatch.
- Retains existing ClosureWaveHistory, ClosureHistory, load_closure_history, build_audit, validate_bundle, and publish_bundle.

- [ ] **Step 1: Add empty-history and aggregate tests first**

Add:

- test_empty_wave_four_requires_zero_progress_zero_journal_and_no_future_admission_artifacts
- test_wave_four_rejects_any_progress_schema_or_proof_namespace_entry
- test_wave_four_rejects_any_v1_v2_or_unknown_journal_event
- test_history_accepts_three_prior_waves_plus_empty_selfask_wave_four_without_credit
- test_four_wave_aggregate_rejects_cross_wave_issue_group_path_or_active_tip_conflict
- test_later_v3_wave_four_failure_preserves_all_prior_public_output_bytes
- test_wave_four_public_overlay_keeps_selfask_review_required_hold_without_terminal_credit

Each negative subcase rebuilds a fresh fixture. The positive aggregate compares the first three histories before and after adding Wave 4 and proves only wave and predecessor counts change.

- [ ] **Step 2: Run causal RED**

~~~powershell
$selectors = @(
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_empty_wave_four_requires_zero_progress_zero_journal_and_no_future_admission_artifacts',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_rejects_any_progress_schema_or_proof_namespace_entry',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_rejects_any_v1_v2_or_unknown_journal_event',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_history_accepts_three_prior_waves_plus_empty_selfask_wave_four_without_credit',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_four_wave_aggregate_rejects_cross_wave_issue_group_path_or_active_tip_conflict',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_later_v3_wave_four_failure_preserves_all_prior_public_output_bytes',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_public_overlay_keeps_selfask_review_required_hold_without_terminal_credit'
)
python -B -X utf8 -m unittest @selectors
~~~

Expected: assertion-level failures at missing v3 empty-history boundaries.

- [ ] **Step 3: Make v3 progress and proof namespace strictly empty**

In _load_progress_rows, after canonical parsing:

1. For CLOSURE_INTAKE_V3_SCHEMA require rows == ().
2. Treat an absent proofs child as empty.
3. If proofs exists, reject symlink/reparse, non-directory, or any entry without following links.
4. Return an empty dict.
5. Leave existing v1/v2 parsing and v2 target-admission handling unchanged.

Use _validate_empty_wave_four_proof_namespace(proof_root: Path) -> None if it makes the branch clearer. It may inspect only proof_root/proofs.

- [ ] **Step 4: Make v3 journals strictly zero bytes**

After reading candidate journal bytes, require candidate_bytes == b"" for v3 and set events=(). Any content fails closure-journal-malformed before legacy event dispatch. Do not define v3 progress or event schemas.

- [ ] **Step 5: Run GREEN and existing rollback controls**

Run Step 2, then:

~~~powershell
$controls = @(
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_history_accepts_wave_one_wave_two_and_empty_reviewed_duplicate_wave_three',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_later_v2_wave_failure_preserves_all_prior_public_output_bytes',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_journal_mutation_during_staged_validation_rolls_back_every_output',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_replace_failure_restores_every_prior_output',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_metrics_is_the_last_replaced_commit_record'
)
python -B -X utf8 -m unittest @controls
~~~

Expected: all pass with only temporary-fixture writes.

- [ ] **Step 6: Run the full dynamic-audit module**

~~~powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
~~~

Expected: exit 0. Record count, duration, failures, errors, and skips. Do not run Gradle.

- [ ] **Step 7: Record Task-3 hashes and scope**

Verify only the two intended Python files changed in Tasks 2-3. Recompute registry, Gradle, public, candidate, future-test absence, RagControl, and older-wave hashes. They remain at Task-1 values.

---

### Task 4: Source-Health Test Fixtures And Shared-Loader Parity

**Files:**

- Modify: scripts/test_source_health_scorecard.py
- Read: scripts/source_health_scorecard.py
- Read: scripts/test_dynamic_rag_quant_audit.py
- Test: scripts/test_source_health_scorecard.py

**Interfaces:**

- Generalizes fixture seeding to copy descriptor-local v1, v2, or v3 intake companions according to each canonical summary schema.
- Adds include_empty_selfask_reviewed_wave_four: bool = False to _write_linked_quant_bundle.
- Reuses install_empty_selfask_reviewed_admission_wave_four from the dynamic-audit test module.
- Keeps source-health production on the shared registry, history, and full-bundle validators.

- [ ] **Step 1: Add explicit source-health v3 fixture tests first**

Add:

- test_current_quant_bundle_reconstructs_empty_selfask_reviewed_wave_four
- test_current_quant_bundle_rejects_tampered_wave_four_admission_bytes
- test_current_quant_bundle_rejects_tampered_wave_four_duplicate_evidence_bytes
- test_source_health_uses_same_registry_history_and_full_bundle_loader_for_wave_four
- test_green_fixture_preserves_requested_duplicate_counts_after_wave_four_install

The positive test installs waves 1 through 4 in a temporary root, pins that fixture registry only inside the existing narrow context, validates through source_health_scorecard.validate_current_quant_bundle, and compares the result to dynamic_rag_quant_audit.load_and_validate_current_bundle.

Each tamper test changes one canonical file after fixture linking, preserves existing output sentinels, runs the canonical source-health main, and asserts return code 2 plus byte-identical sentinels.

- [ ] **Step 2: Run causal RED**

~~~powershell
$selectors = @(
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_empty_selfask_reviewed_wave_four',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_rejects_tampered_wave_four_admission_bytes',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_rejects_tampered_wave_four_duplicate_evidence_bytes',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_source_health_uses_same_registry_history_and_full_bundle_loader_for_wave_four',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_green_fixture_preserves_requested_duplicate_counts_after_wave_four_install'
)
python -B -X utf8 -m unittest @selectors
~~~

Expected: assertion-level failures because fixture installation understands only through Wave 3.

- [ ] **Step 3: Generalize registered-history fixture seeding by schema**

In _seed_empty_registered_history, preserve the three base intake/progress copies. Copy admission-decision.json and duplicate-evidence.json when schemaVersion is either CLOSURE_INTAKE_V2_SCHEMA or CLOSURE_INTAKE_V3_SCHEMA. Accept CLOSURE_INTAKE_SCHEMA without companions and reject any other schema. Derive behavior from each descriptor; do not hard-code the live wave count.

- [ ] **Step 4: Add explicit Wave-4 fixture installation**

Extend _write_linked_quant_bundle with the exact keyword:

~~~python
include_empty_selfask_reviewed_wave_four: bool = False
~~~

Required sequence:

1. Seed the fixture's registered histories.
2. Install empty Wave 3 when explicitly requested or when the copied registry contains at least three descriptors.
3. Install empty Wave 4 only after Wave 3 when explicitly requested or when the copied registry contains at least four descriptors.
4. Preserve caller-provided duplicate-report bytes across both installers and restore them before the final fixture audit.
5. Pin the final fixture registry hash only inside _fixture_registry_pin or its existing equivalent.
6. Keep _empty_closure_history_summary(root) derived from the actual fixture registry and canonical sorted descriptor-relative journal rows.
7. Assert the installed Wave-4 intake remains v3 with one predecessor and zero events/proofs/admissions.

Do not add SelfAsk schema logic to scripts/source_health_scorecard.py.

- [ ] **Step 5: Run GREEN and existing source-health controls**

Run Step 2, then:

~~~powershell
$controls = @(
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_registry_backed_v2_history',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_empty_reviewed_duplicate_wave_three',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_rejects_tampered_wave_three_admission_bytes',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_rejects_registry_or_wave_identity_drift',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_main_current_linked_quant_bundle_writes_registry_backed_scorecard',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_green_fixture_uses_packaged_active_duplicate_semantics'
)
python -B -X utf8 -m unittest @controls
~~~

Expected: all pass and shared loader call counts stay one per route.

- [ ] **Step 6: Run the full source-health module**

~~~powershell
python -B -X utf8 scripts/test_source_health_scorecard.py
~~~

Expected: exit 0. If a failure proves a production delegation gap rather than fixture coupling, stop and return to design review before modifying scripts/source_health_scorecard.py.

- [ ] **Step 7: Inspect Task-4 diff and preservation boundary**

Verify scripts/source_health_scorecard.py remains byte-identical to Task 1. Recheck all production/config/public/Wave/candidate/future-test/RagControl hashes. Only the three intended Python files may differ from Task 1.

---

### Task 5: Three-Wave Infrastructure Green Gate Before The Final Producer

**Files:**

- Read/test: scripts/dynamic_rag_quant_audit.py
- Read/test: scripts/test_dynamic_rag_quant_audit.py
- Read/test: scripts/source_health_scorecard.py
- Read/test: scripts/test_source_health_scorecard.py
- Read: build.gradle.kts, registry, registered waves, public artifacts, candidates, absent future test, and HOLD paths
- Modify: none

**Interfaces:**

- Produces a frozen infrastructure postimage checkpoint. No production/test byte may change between this checkpoint and Task-6 Stage-A capture.

- [ ] **Step 1: Run the combined focused ladder**

Run every new Task-2, Task-3, and Task-4 selector in one unittest invocation, followed by existing v1/v2 parity, rollback, source-health tamper, registry, and Gradle-contract selectors. Require zero failures, errors, and skips. Preserve exact command output and test count.

- [ ] **Step 2: Run both full modules sequentially**

~~~powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
~~~

Expected: both exit 0. Record exact counts and raw hashes for the two production modules and two test modules.

- [ ] **Step 3: Prove registry and Gradle remain pre-registration**

Require:

~~~text
registry SHA-256=e1791b53a62d7dd1f24e6c4598b1652c353c9c4c86c22e6994b0dd895c14db4b
registry descriptors=3
imported registry pin equals raw registry SHA-256
build.gradle.kts SHA-256=ef3828a9ae24c25f4f6fcb2ac66f5fbccf41d5a6ef18638bebdab88db52acfc1
structuralRepairWaveFour declaration count=0
Wave-4 file count=0
~~~

- [ ] **Step 4: Revalidate public artifacts and protected paths**

Run both current-bundle validators. Recompute all registered-wave hashes, the four existing target hashes and states, future-test absence, both RagControl hashes, staged/index-lock/PatchDrop/lease gates, and Wave-4 path absence.

- [ ] **Step 5: Freeze Task-5 postimages**

Record a sorted path/size/hash/status manifest for:

- the four Python production/test files;
- approved spec and plan;
- registry and both Gradle files;
- current public artifacts;
- every registered-wave input and referenced proof;
- the four existing SelfAsk/guard targets;
- the absent future test;
- both RagControl files.

After this checkpoint and through Task-6 file creation, no Python, config, registry, candidate, HOLD, or older-wave byte may change. Any drift discards Stage-A and returns to the relevant earlier task.

---

### Task 6: Final Fresh Audit, Exact Stage-A Capture, And Unregistered Wave-4 Freeze

**Files:**

- Generate through existing producer: verification/structural-design-baseline.json
- Generate through existing producer: verification/structural-design-debt-ledger.jsonl
- Generate through existing producer: verification/dynamic-rag-quant-audit-metrics.json
- Generate through producer dependency: app/build/desktop-wave4-selfask/reports/dup-fqcn-evidence.json
- Inspect from the same producer dependency: app/build/desktop-wave4-selfask/generated/dup-fqcn-excludes.txt
- Create with apply_patch: five nonempty Wave-4 intake files
- Create with apply_patch: zero-byte Wave-4 progress and journal
- Modify no production, test, Gradle, registry, Java, source-health, or older-wave file

**Interfaces:**

- Consumes frozen Task-5 infrastructure and exactly one dynamicRagQuantAudit invocation.
- Produces one indivisible Stage-A tuple and one directly loadable but unregistered empty ClosureWaveDescriptor.

- [ ] **Step 1: Recheck Task-5 hashes and initialize isolated caches**

Stop if any Task-5 hash, candidate state, future-test absence, RagControl hash, branch/HEAD, staged count, index lock, PatchDrop count, or lease count changed.

~~~powershell
$wave4CacheRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('awx-wave4-selfask-' + [guid]::NewGuid().ToString('N'))
$wave4GradleHome = Join-Path $wave4CacheRoot 'gradle-user-home'
$wave4ProjectCache = Join-Path $wave4CacheRoot 'project-cache'
New-Item -ItemType Directory -Path $wave4GradleHome -Force | Out-Null
New-Item -ItemType Directory -Path $wave4ProjectCache -Force | Out-Null
$env:GRADLE_USER_HOME = $wave4GradleHome
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-wave4-selfask'
$env:PYTHONDONTWRITEBYTECODE = '1'
[pscustomobject]@{cacheRoot=$wave4CacheRoot;gradleUserHome=$wave4GradleHome;projectCache=$wave4ProjectCache;splitOutputs=$env:AWX_SPLIT_BUILD_OUTPUTS;hostId=$env:AWX_BUILD_HOST_ID} | Format-List
~~~

Preserve these cache paths through Task 8. Do not reuse the Wave-3 cache.

- [ ] **Step 2: Run exactly one final pre-registration producer**

~~~powershell
$producerStarted = [DateTimeOffset]::UtcNow
.\gradlew.bat --no-daemon --project-cache-dir $wave4ProjectCache dynamicRagQuantAudit
$producerExit = $LASTEXITCODE
$producerFinished = [DateTimeOffset]::UtcNow
if ($producerExit -ne 0) { throw "wave4-stage-a-producer-failed:$producerExit" }
$env:AWX_WAVE4_PRODUCER_STARTED_UTC = $producerStarted.ToString('O')
$env:AWX_WAVE4_PRODUCER_FINISHED_UTC = $producerFinished.ToString('O')
~~~

Expected: exit 0. Do not run sourceHealthScorecard, dynamicRagQuantAudit, or any dependent Gradle task again before Task 7.

- [ ] **Step 3: Build and print the five canonical intake payloads without writing**

Run the following from the repository root. It emits five canonical payload records and one count/hash-only STAGE_A record.

~~~powershell
@'
from pathlib import Path
from datetime import datetime, timezone
import copy
import hashlib
import os
import re

from scripts import dynamic_rag_quant_audit as audit
from scripts import source_health_scorecard as score

root = Path(".").resolve()
spec_path = root / "docs/superpowers/specs/2026-09-03-wave-four-selfask-reviewed-admission-design.md"
report_path = root / "app/build/desktop-wave4-selfask/reports/dup-fqcn-evidence.json"
exclude_path = root / "app/build/desktop-wave4-selfask/generated/dup-fqcn-excludes.txt"
approved_design_sha = "1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded"
assert hashlib.sha256(spec_path.read_bytes()).hexdigest() == approved_design_sha

bundle = audit.load_and_validate_current_bundle(root=root)
assert score.validate_current_quant_bundle(root) == bundle.metrics
started = datetime.fromisoformat(os.environ["AWX_WAVE4_PRODUCER_STARTED_UTC"])
finished = datetime.fromisoformat(os.environ["AWX_WAVE4_PRODUCER_FINISHED_UTC"])
report_raw = report_path.read_bytes()
exclude_raw = exclude_path.read_bytes()
report_input = audit.load_required_input(report_path, datetime.now(timezone.utc), require_freshness=True)
report_generated = audit._parse_timestamp(report_input["generatedAt"])
assert started.astimezone(timezone.utc) <= report_generated <= finished.astimezone(timezone.utc)
duplicate = audit._validate_duplicate_input(report_input)
exclude_lines = [
    line.strip()
    for line in exclude_raw.decode("utf-8").splitlines()
    if line.strip() and not line.lstrip().startswith("#")
]
assert exclude_lines.count("service/rag/planner/SelfAskPlanner*") == 1

core_rows = []
for envelope in bundle.ledger_rows:
    row = copy.deepcopy(envelope)
    for field in ("generatedAt", "auditRunId", "artifactLinks"):
        row.pop(field)
    core_rows.append(row)
selfask_rows = [
    row for row in core_rows
    if row.get("category") == "DUPLICATE_FQCN_SOURCE_COLLISION"
    and row.get("severity") == "HIGH"
    and row.get("fixEligibility") == "REVIEW_REQUIRED"
    and row.get("status") == "HOLD"
    and row.get("symbol") == "service.rag.planner.SelfAskPlanner"
    and row.get("path") == "main/java/service/rag/planner/SelfAskPlanner.java"
    and row.get("supersedes") == []
    and row.get("numericEvidence") == {
        "duplicateFqcnGeneratedExcludeCount": 1,
        "duplicateFqcnHardExcludeCount": 0,
        "duplicateFqcnPackagedActiveCount": 0,
        "duplicateFqcnSourceCollisionCount": 1,
    }
]
assert len(selfask_rows) == 1
predecessor = selfask_rows[0]
collision_rows = [
    row for row in duplicate["collisions"]
    if row.get("fqcn") == "service.rag.planner.SelfAskPlanner"
    and row.get("rootPath") == "main/java/service/rag/planner/SelfAskPlanner.java"
    and row.get("appPath") == "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java"
    and row.get("packagingState") == "GENERATED_EXCLUDE"
]
assert len(collision_rows) == 1
collision = collision_rows[0]
assert re.fullmatch(r"[0-9a-f]{64}", collision["evidenceFingerprint"])
caller_count = audit._count_selfask_compatibility_copy_direct_callers(
    root=root,
    compatibility_copy_path="app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
)
assert caller_count == 0

target_paths = (
    "app/build.gradle.kts",
    "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
    "main/java/config/RagLightAdapters.java",
    "main/java/service/rag/planner/SelfAskPlanner.java",
    "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java",
)
expected_hashes = {
    "app/build.gradle.kts": "b1590e63596000c944d6a1e94bf325323d001d41ba8cc9360e3462f1dd4c4597",
    "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java": "98598256965d3ff9978c763d3639a8d9017062c6a5a7c820d803adbaa1d16b64",
    "main/java/config/RagLightAdapters.java": "9974081bff3e38182efc40cf659176cdc6bf62022496f3ed8cbafd896becfc95",
    "main/java/service/rag/planner/SelfAskPlanner.java": "7c1a82407fa741d5aa7ed5509ddd9d6779a00055f79eccab674af52eb8834786",
}
manifest_by_path = {row["path"]: row for row in bundle.baseline["pathStateContentRows"]}
target_rows = []
for path in target_paths:
    candidate = root.joinpath(*path.split("/"))
    if path.endswith("SelfAskPlannerOwnershipContractTest.java"):
        assert not os.path.lexists(candidate)
        assert path not in manifest_by_path
        target_rows.append({"path":path,"existence":"ABSENT","gitState":"absent","sizeBytes":0,"sha256":"0"*64})
        continue
    assert candidate.is_file()
    raw = candidate.read_bytes()
    raw_hash = hashlib.sha256(raw).hexdigest()
    assert raw_hash == expected_hashes[path]
    manifest_row = manifest_by_path[path]
    assert manifest_row["sizeBytes"] == len(raw)
    assert manifest_row["sha256"] == raw_hash
    if path == "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java":
        assert manifest_row["gitState"] == "clean"
    target_rows.append({"path":path,"existence":"FILE","gitState":manifest_row["gitState"],"sizeBytes":len(raw),"sha256":raw_hash})

target_preimages = {
    "schemaVersion": audit.CLOSURE_TARGET_PREIMAGES_SCHEMA,
    "sourceHead": bundle.baseline["head"],
    "canonicalBranch": bundle.baseline["branch"],
    "isolatedBranch": bundle.baseline["branch"],
    "targets": target_rows,
}
evidence = {
    "schemaVersion": audit.CLOSURE_DUPLICATE_EVIDENCE_V2_SCHEMA,
    "sourceIssueId": predecessor["issueId"],
    "rootCauseGroupId": predecessor["rootCauseGroupId"],
    "fqcn": "service.rag.planner.SelfAskPlanner",
    "canonicalOwnerPath": "main/java/service/rag/planner/SelfAskPlanner.java",
    "compatibilityCopyPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
    "packagingState": "GENERATED_EXCLUDE",
    "sourceCollisionCount": 1,
    "generatedExcludeCount": 1,
    "hardExcludeCount": 0,
    "packagedActiveCount": 0,
    "appActiveSourceRoot": "app/src/main/java_clean",
    "compatibilityCopyDirectCallerFileCount": caller_count,
    "callerProbeToken": "java-active-app-direct-caller-files-v1",
    "generatedExcludePattern": "service/rag/planner/SelfAskPlanner*",
    "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
    "rawReportSha256": hashlib.sha256(report_raw).hexdigest(),
    "rawCollisionEvidenceFingerprint": collision["evidenceFingerprint"],
    "secretPatternHitCount": 0,
}
evidence_bytes = audit.canonical_json_bytes(evidence)
decision = {
    "schemaVersion": audit.CLOSURE_ADMISSION_DECISION_V2_SCHEMA,
    "decisionType": "EXACT_SELFASK_REVIEWED_DUPLICATE_OWNER",
    "sourceBaselineId": predecessor["baselineId"],
    "sourceIssueId": predecessor["issueId"],
    "rootCauseGroupId": predecessor["rootCauseGroupId"],
    "sourceEvidenceFingerprint": predecessor["evidenceFingerprint"],
    "sourceCategory": predecessor["category"],
    "sourceFixEligibility": predecessor["fixEligibility"],
    "sourceStatus": predecessor["status"],
    "sourceNumericEvidence": predecessor["numericEvidence"],
    "candidateFqcn": "service.rag.planner.SelfAskPlanner",
    "canonicalOwnerPath": "main/java/service/rag/planner/SelfAskPlanner.java",
    "proposedRepairTargetPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
    "repairTargetAdmissionRequired": True,
    "packagingConfigPath": "app/build.gradle.kts",
    "activeCallPath": "main/java/config/RagLightAdapters.java",
    "futureOwnershipTestPath": "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java",
    "duplicateEvidenceSha256": hashlib.sha256(evidence_bytes).hexdigest(),
    "approvedDesignSha256": approved_design_sha,
    "ragControlExcluded": True,
}
decision["decisionId"] = hashlib.sha256(audit.canonical_json_bytes({key:value for key,value in decision.items() if key!="decisionId"})).hexdigest()
decision_bytes = audit.canonical_json_bytes(decision)
summary = {
    "schemaVersion": audit.CLOSURE_INTAKE_V3_SCHEMA,
    "sourceBaselineId": predecessor["baselineId"],
    "sourceLedgerPayloadSha256": bundle.metrics["artifactLinks"]["ledgerPayloadSha256"],
    "sourceMetricsSemanticHash": bundle.metrics["semanticArtifactHash"],
    "sourceIssueIds": [predecessor["issueId"]],
    "rootCauseGroupIds": [predecessor["rootCauseGroupId"]],
    "evidenceFingerprints": [predecessor["evidenceFingerprint"]],
    "sourceBranch": bundle.baseline["branch"],
    "sourceHead": bundle.baseline["head"],
    "admissionMode": "EXACT_SELFASK_REVIEW_PROMOTION",
    "admissionDecisionSha256": hashlib.sha256(decision_bytes).hexdigest(),
    "duplicateEvidenceSha256": hashlib.sha256(evidence_bytes).hexdigest(),
}
payloads = {
    "duplicate-evidence.json": evidence_bytes,
    "admission-decision.json": decision_bytes,
    "eligible-groups.jsonl": audit.canonical_ndjson_bytes((predecessor,)),
    "target-preimages.json": audit.canonical_json_bytes(target_preimages),
    "intake-summary.json": audit.canonical_json_bytes(summary),
}
for name in ("duplicate-evidence.json","admission-decision.json","eligible-groups.jsonl","target-preimages.json","intake-summary.json"):
    raw = payloads[name]
    text = raw.decode("utf-8")
    assert not audit.SECRET_PATTERN.search(text)
    assert not audit.WINDOWS_ABSOLUTE_PATTERN.search(text)
    print("PAYLOAD " + name + " " + text, end="")

rag_paths = (
    "main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java",
    "src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java",
)
stage_a = {
    "schemaVersion": "awx.wave-four-selfask-stage-a.v1",
    "baselineId": bundle.baseline["baselineId"],
    "branch": bundle.baseline["branch"],
    "head": bundle.baseline["head"],
    "sourceSetHash": bundle.baseline["activeSourceSetConfigurationHash"],
    "manifestSha256": hashlib.sha256(audit.canonical_json_bytes(bundle.baseline["pathStateContentRows"])).hexdigest(),
    "publicRawHashes": {path:hashlib.sha256((root/path).read_bytes()).hexdigest() for path in ("verification/structural-design-baseline.json","verification/structural-design-debt-ledger.jsonl","verification/dynamic-rag-quant-audit-metrics.json")},
    "duplicateReportSha256": hashlib.sha256(report_raw).hexdigest(),
    "generatedExcludeSha256": hashlib.sha256(exclude_raw).hexdigest(),
    "predecessorIssueId": predecessor["issueId"],
    "predecessorRootCauseGroupId": predecessor["rootCauseGroupId"],
    "predecessorEvidenceFingerprint": predecessor["evidenceFingerprint"],
    "callerFileCount": caller_count,
    "targetRows": target_rows,
    "ragControlHashes": {path:hashlib.sha256((root/path).read_bytes()).hexdigest() for path in rag_paths},
    "payloadHashes": {name:hashlib.sha256(raw).hexdigest() for name,raw in sorted(payloads.items())},
}
print("STAGE_A " + audit.canonical_json_bytes(stage_a).decode("utf-8"), end="")
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'wave-four-stage-a-capture-failed' }
~~~

Expected: exactly five PAYLOAD records and one STAGE_A record. Every future ID/hash comes from these fresh bytes; never substitute the provisional design values.

- [ ] **Step 4: Recheck Stage-A freshness before any Wave-4 write**

Without running Gradle:

1. Rehash the three public artifacts, detailed report, and generated-exclude file against STAGE_A.
2. Rehash the four existing targets and require exact target rows.
3. Require the future test still absent.
4. Recompute caller count and require 0.
5. Rehash both RagControl paths.
6. Rehash every Task-5 production/test file.
7. Require branch/HEAD, staged, lock, PatchDrop, and lease gates unchanged.

Any mismatch discards all printed payloads and returns to Task 5.

- [ ] **Step 5: Create exactly seven Wave-4 files with apply_patch**

Create:

~~~text
.superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json
.superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json
.superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl
.superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json
.superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json
.superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl
verification/structural-repair-waves/wave-0004/journal.jsonl
~~~

Paste the five exact canonical payloads. Add the last two files with zero content. Create no proof, repair-target admission, directory marker, raw report copy, or source file.

- [ ] **Step 6: Reparse every frozen file and validate the unregistered descriptor**

~~~powershell
@'
from pathlib import Path
import hashlib
from scripts import dynamic_rag_quant_audit as audit

root = Path(".").resolve()
proof_root = root / ".superpowers/sdd/structural-repair-waves/wave-0004"
journal = root / "verification/structural-repair-waves/wave-0004/journal.jsonl"
summary_path = proof_root / audit.INTAKE_SUMMARY_RELATIVE
rows_path = proof_root / audit.INTAKE_ROWS_RELATIVE
targets_path = proof_root / audit.TARGET_PREIMAGES_RELATIVE
summary = audit._parse_canonical_json_bytes(summary_path.read_bytes(), "closure-intake-mismatch")
audit._parse_canonical_ndjson_bytes(rows_path.read_bytes(), "closure-intake-mismatch")
audit._parse_canonical_json_bytes(targets_path.read_bytes(), "closure-intake-mismatch")
for relative in (audit.ADMISSION_DECISION_RELATIVE, audit.DUPLICATE_EVIDENCE_RELATIVE):
    audit._parse_canonical_json_bytes((proof_root/relative).read_bytes(), "closure-admission-invalid")
assert (proof_root/audit.PROGRESS_RELATIVE).read_bytes() == b""
assert journal.read_bytes() == b""
descriptor = audit.ClosureWaveDescriptor(
    wave_id="wave-0004",
    ordinal=4,
    journal_path=journal.resolve(),
    proof_root=proof_root.resolve(),
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
print({
    "waveFourDirectValidation": "PASS",
    "predecessors": 1,
    "events": 0,
    "proofPairs": 0,
    "waveId": descriptor.wave_id,
    "ordinal": descriptor.ordinal,
    "sourceBaselineId": descriptor.source_baseline_id,
    "sourceLedgerPayloadSha256": descriptor.source_ledger_payload_sha256,
    "sourceMetricsSemanticHash": descriptor.source_metrics_semantic_hash,
    "intakeSummarySha256": descriptor.intake_summary_sha256,
    "eligibleGroupsSha256": descriptor.eligible_groups_sha256,
    "targetPreimagesSha256": descriptor.target_preimages_sha256,
})
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'wave-four-direct-validation-failed' }
~~~

- [ ] **Step 7: Enforce no-intervening-producer and protected-byte boundaries**

Rehash the three public artifacts, detailed report, generated-exclude file, targets, RagControl, and Task-5 Python files; require exact Stage-A/Task-5 equality. Recompute caller count and require the exact SelfAsk generated pattern once. Do not run Gradle.

- [ ] **Step 8: Record Task-6 no-commit checkpoint**

Expected repository effects are the producer-regenerated public artifacts and exactly seven new Wave-4 files. Registry and root Gradle remain at Task-1 preimages. No Java, app Gradle, call path, future test, RagControl, older wave, stage, commit, or push changed.

---

### Task 7: Four-Wave Registry Pin, Exact Gradle Inputs, And First Publication

**Files:**

- Modify: scripts/dynamic_rag_quant_audit.py, registry pin only
- Modify: scripts/test_dynamic_rag_quant_audit.py, live registry and Gradle assertions
- Modify: scripts/test_source_health_scorecard.py, live four-wave assertion
- Modify: build.gradle.kts
- Modify: verification/structural-repair-waves/registry.json
- Regenerate: public quantitative artifacts and source-health outputs through existing tasks
- Test: both Python modules and both public Gradle tasks

**Interfaces:**

- Consumes the directly validated Wave-4 descriptor from Task 6.
- Produces one code-pinned canonical four-wave registry, exact fixed Gradle inputs, a linked public bundle, and source-health output.

- [ ] **Step 1: Add live registry and Gradle tests first**

Add or update:

- test_closure_registry_accepts_exact_canonical_four_wave_descriptor_set
- test_gradle_uses_only_the_fixed_closure_wave_registry_contract
- test_current_quant_bundle_reconstructs_empty_selfask_reviewed_wave_four

The registry test requires ordinals 1, 2, 3, 4; exact wave IDs; semantic equality of descriptors 1-3 to their pre-transaction objects; and registry SHA equal to the imported pin.

Keep the class-wide synthetic fixture pin at its existing two-wave payload. Every test that loads the real ROOT registry must derive the raw live registry hash locally and patch only that test's call boundary. Do not replace the fixture default with the four-wave pin; that would make generic two-wave fixtures fail before their intended assertions.

The Gradle test requires exactly:

~~~text
verification/structural-repair-waves/wave-0004/journal.jsonl
.superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json
.superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl
.superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json
.superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json
.superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json
.superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl
~~~

Both public tasks apply PathSensitivity.RELATIVE. Reject inputs.dir for Wave 4, structuralRepairWaveFourProofRoot, a Wave-4 file tree or proof glob, broad .superpowers input, and raw build-report input.

- [ ] **Step 2: Run the three causal RED selectors**

~~~powershell
$selectors = @(
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_closure_registry_accepts_exact_canonical_four_wave_descriptor_set',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_gradle_uses_only_the_fixed_closure_wave_registry_contract',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_empty_selfask_reviewed_wave_four'
)
python -B -X utf8 -m unittest @selectors
~~~

Expected: assertion-level RED because live registry, pin, and Gradle still describe three waves. Discovery failure is not accepted.

- [ ] **Step 3: Print canonical four-wave registry bytes without writing**

~~~powershell
@'
from pathlib import Path
import copy
import hashlib
from scripts import dynamic_rag_quant_audit as audit

root = Path(".").resolve()
registry_path = root / audit.CLOSURE_WAVE_REGISTRY_RELATIVE
registry = audit._parse_canonical_json_bytes(registry_path.read_bytes(), "closure-registry-malformed")
assert len(registry["waves"]) == 3
first_three = copy.deepcopy(registry["waves"])
proof_root = root / ".superpowers/sdd/structural-repair-waves/wave-0004"
summary_path = proof_root / audit.INTAKE_SUMMARY_RELATIVE
rows_path = proof_root / audit.INTAKE_ROWS_RELATIVE
targets_path = proof_root / audit.TARGET_PREIMAGES_RELATIVE
summary = audit._parse_canonical_json_bytes(summary_path.read_bytes(), "closure-intake-mismatch")
fourth = {
    "waveId": "wave-0004",
    "ordinal": 4,
    "journalPath": "verification/structural-repair-waves/wave-0004/journal.jsonl",
    "proofRoot": ".superpowers/sdd/structural-repair-waves/wave-0004",
    "sourceBaselineId": summary["sourceBaselineId"],
    "sourceLedgerPayloadSha256": summary["sourceLedgerPayloadSha256"],
    "sourceMetricsSemanticHash": summary["sourceMetricsSemanticHash"],
    "intakeSummarySha256": hashlib.sha256(summary_path.read_bytes()).hexdigest(),
    "eligibleGroupsSha256": hashlib.sha256(rows_path.read_bytes()).hexdigest(),
    "targetPreimagesSha256": hashlib.sha256(targets_path.read_bytes()).hexdigest(),
}
payload = audit.canonical_json_bytes({"schemaVersion":audit.CLOSURE_WAVE_REGISTRY_SCHEMA,"waves":[*first_three,fourth]})
print("registrySha256=" + hashlib.sha256(payload).hexdigest())
print(payload.decode("utf-8"), end="")
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'prospective-wave-four-registry-failed' }
~~~

Retain the exact payload and hash. Do not hand-edit measured Stage-A fields.

- [ ] **Step 4: Freeze exact registration rollback preimages**

Capture exact raw bytes or reconstructible apply_patch diffs for:

~~~text
verification/structural-repair-waves/registry.json
scripts/dynamic_rag_quant_audit.py
build.gradle.kts
scripts/test_dynamic_rag_quant_audit.py
scripts/test_source_health_scorecard.py
~~~

The dynamic-audit preimage now includes v3 support and the old three-wave pin. A rollback must not restore the Task-1 file and remove v3 support.

- [ ] **Step 5: Apply registry, code pin, and exact Gradle declarations as one scoped transaction**

Use apply_patch to replace registry.json with Step-3 bytes, replace only FROZEN_CLOSURE_WAVE_REGISTRY_SHA256 with the Step-3 hash, and add:

~~~kotlin
val structuralRepairWaveFourJournal =
    layout.projectDirectory.file("verification/structural-repair-waves/wave-0004/journal.jsonl")
val structuralRepairWaveFourFixedInputs = files(
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl"
    ),
)
~~~

Add to both dynamicRagQuantAudit and sourceHealthScorecard:

~~~kotlin
inputs.file(structuralRepairWaveFourJournal)
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
inputs.files(structuralRepairWaveFourFixedInputs)
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
~~~

Do not add a Wave-4 proof root, file tree, or CLI argument.

- [ ] **Step 6: Run focused registry, version, Gradle, aggregate, and source-health tests**

Run Step 2, then:

~~~powershell
$controls = @(
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_three_v2_intake_accepts_one_exact_reviewed_duplicate_predecessor',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_wave_four_v3_intake_accepts_exact_selfask_reviewed_predecessor',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_load_closure_wave_preserves_wave_one_predecessors_events_and_proofs',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_history_accepts_three_prior_waves_plus_empty_selfask_wave_four_without_credit',
  'scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_cli_requires_only_the_fixed_closure_wave_registry_argument',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_registry_backed_v2_history',
  'scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_current_quant_bundle_reconstructs_empty_reviewed_duplicate_wave_three'
)
python -B -X utf8 -m unittest @controls
~~~

Expected: all pass.

Run a read-only caller inventory for root=ROOT, ROOT.resolve(), load_closure_registry, load_closure_history, and load_and_validate_current_bundle in both test modules. Confirm every live-root call uses an explicit live registry pin and every temporary fixture uses its fixture-specific pin.

- [ ] **Step 7: Validate direct four-wave history before publication**

~~~powershell
python -B -X utf8 -c "from pathlib import Path; from scripts import dynamic_rag_quant_audit as a; root=Path('.').resolve(); registry=a.load_closure_registry(root=root,registry_path=a.CLOSURE_WAVE_REGISTRY_RELATIVE); assert len(registry.waves)==4; history=a.load_closure_history(root=root,registry=registry); assert history.wave_count==4; assert len(history.predecessor_rows)==17; assert history.event_count==15; assert len(history.active_events)==15; assert history.rejected_false_positive_root_cause_groups==5; assert history.verified_closed_root_cause_groups==10; print(history.summary)"
~~~

Expected: 4 / 17 / 15 / 15 / 5 / 10.

- [ ] **Step 8: Publish linked quantitative artifacts**

~~~powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave4ProjectCache dynamicRagQuantAudit
~~~

Expected: exit 0; existing staged validation precedes baseline, ledger, and metrics replacement.

- [ ] **Step 9: Run source health through its dependency chain**

~~~powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave4ProjectCache sourceHealthScorecard
~~~

Expected: exit 0. The declared dependency may rerun dynamicRagQuantAudit once. Capture final public hashes after this entire chain.

- [ ] **Step 10: Run both full Python modules after publication**

~~~powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
~~~

Expected: both exit 0. Running live-root full modules after pinning but before public publication is prohibited.

- [ ] **Step 11: Validate public counters and SelfAsk no-credit state**

~~~powershell
python -B -X utf8 -c "from pathlib import Path; from scripts import dynamic_rag_quant_audit as a; from scripts import source_health_scorecard as s; root=Path('.').resolve(); bundle=a.load_and_validate_current_bundle(root=root); metrics=s.validate_current_quant_bundle(root); assert metrics==bundle.metrics; h=metrics['closureHistorySummary']; l=metrics['ledgerSummary']; assert h['waveCount']==4 and h['eventCount']==15 and h['rejectedFalsePositiveRootCauseGroups']==5 and h['verifiedClosedRootCauseGroups']==10; assert l['eligibleRootCauseGroups']==1 and l['verifiedClosedRootCauseGroups']==10 and l['targetGap']==889; rows=[row for row in bundle.ledger_rows if row.get('symbol')=='service.rag.planner.SelfAskPlanner']; assert len(rows)==1 and rows[0]['fixEligibility']=='REVIEW_REQUIRED' and rows[0]['status']=='HOLD'; history=a.load_closure_history(root=root,registry=bundle.closure_registry); assert not any(event.get('sourceIssueId')==rows[0]['issueId'] for event in history.active_events); print({'closure':h,'eligible':1,'targetGap':889,'selfAskState':'REVIEW_REQUIRED/HOLD','repairCreditAdded':0})"
~~~

- [ ] **Step 12: Apply fail-closed rollback if any post-registration gate fails**

If a failure occurs after Step 5:

1. Stop before another producer attempt.
2. Restore with apply_patch the exact Step-4 registry bytes, v3-capable dynamic-audit bytes with the old three-wave pin, and pre-transaction build.gradle.kts.
3. Restore only registration-specific live four-wave test assertions; retain fixture-level v3 tests.
4. Validate the old three-wave registry and last coherent three-wave public bundle through both validators.
5. Leave seven Wave-4 files as unregistered private evidence; they grant no loader authority or credit.
6. Report the first failure and do not clean or delete those files.

Do not use reset, checkout, clean, stash, or memory-based byte reconstruction.

- [ ] **Step 13: Record Task-7 no-commit checkpoint**

Hash registry, imported pin, Gradle, seven Wave-4 files, final public artifacts, source-health output, candidates, absent test, RagControl, and older waves. Require zero progress bytes, zero journal bytes, zero proof entries, staged count 0, no index lock, no top-level patch, no active source lease, and no application-source diff introduced by this plan.

---

### Task 8: Determinism, Privacy, Preservation, Final Diff, And Handoff

**Files:**

- Read: every changed file and final public/private evidence artifact
- Regenerate once: sourceHealthScorecard and its declared dependency
- Modify no production, test, registry, Gradle, intake, journal, progress, proof, source, or HOLD file
- Create or amend only the ignored execution report with apply_patch after all gates pass

**Interfaces:**

- Consumes the registered and published four-wave state.
- Produces two non-overwriting normalized projections, a final scoped manifest, and a handoff that leaves the parent goal incomplete.

- [ ] **Step 1: Capture normalized projection A in the preserved cache**

Set:

~~~powershell
$projectionA = Join-Path $wave4CacheRoot 'task-8-projection-a.json'
$env:AWX_WAVE4_PROJECTION_PATH = $projectionA
~~~

Run:

~~~powershell
@'
from pathlib import Path
import copy
import hashlib
import os
from datetime import datetime, timezone
from scripts import dynamic_rag_quant_audit as audit
from scripts import source_health_scorecard as score

root = Path(".").resolve()
bundle = audit.load_and_validate_current_bundle(root=root)
assert score.validate_current_quant_bundle(root) == bundle.metrics
history = audit.load_closure_history(root=root, registry=bundle.closure_registry)
duplicate_path = root / "app/build/desktop-wave4-selfask/reports/dup-fqcn-evidence.json"
generated_exclude_path = root / "app/build/desktop-wave4-selfask/generated/dup-fqcn-excludes.txt"
duplicate = audit._validate_duplicate_input(
    audit.load_required_input(
        duplicate_path,
        datetime.now(timezone.utc),
        require_freshness=False,
    )
)
selfask_collisions = [
    row for row in duplicate["collisions"]
    if row.get("fqcn") == "service.rag.planner.SelfAskPlanner"
]
assert len(selfask_collisions) == 1
core_rows = []
for envelope in bundle.ledger_rows:
    row = copy.deepcopy(envelope)
    for field in ("generatedAt", "auditRunId", "artifactLinks"):
        row.pop(field)
    core_rows.append(row)
wave_four = bundle.closure_registry.waves[3]
projection = {
    "auditRunId": bundle.metrics["auditRunId"],
    "baselineId": bundle.baseline["baselineId"],
    "ledgerCoreRows": core_rows,
    "artifactLinks": bundle.metrics["artifactLinks"],
    "semanticArtifactHash": bundle.metrics["semanticArtifactHash"],
    "closureHistorySummary": bundle.metrics["closureHistorySummary"],
    "ledgerSummary": bundle.metrics["ledgerSummary"],
    "waveFourDescriptor": {
        "waveId": wave_four.wave_id,
        "ordinal": wave_four.ordinal,
        "sourceBaselineId": wave_four.source_baseline_id,
        "sourceLedgerPayloadSha256": wave_four.source_ledger_payload_sha256,
        "sourceMetricsSemanticHash": wave_four.source_metrics_semantic_hash,
        "intakeSummarySha256": wave_four.intake_summary_sha256,
        "eligibleGroupsSha256": wave_four.eligible_groups_sha256,
        "targetPreimagesSha256": wave_four.target_preimages_sha256,
    },
    "eventIds": sorted(event["eventId"] for event in history.all_events),
    "duplicateSemanticHash": duplicate["semanticHash"],
    "selfAskCollisionEvidenceFingerprint": selfask_collisions[0]["evidenceFingerprint"],
    "generatedExcludeSha256": hashlib.sha256(
        generated_exclude_path.read_bytes()
    ).hexdigest(),
    "sourceHealthBundleMatches": True,
}
payload = audit.canonical_json_bytes(projection)
path = Path(os.environ["AWX_WAVE4_PROJECTION_PATH"])
path.write_bytes(payload)
print({"projectionSha256":hashlib.sha256(payload).hexdigest(),"projectionBytes":len(payload)})
'@ | python -B -X utf8 -
if ($LASTEXITCODE -ne 0) { throw 'wave-four-projection-a-failed' }
~~~

Record hash and byte count. This file is outside the repository.

- [ ] **Step 2: Run exactly one additional final producer-dependent chain**

~~~powershell
.\gradlew.bat --no-daemon --project-cache-dir $wave4ProjectCache sourceHealthScorecard
~~~

Expected: exit 0. No retry precedes comparison.

- [ ] **Step 3: Capture projection B and require byte identity**

Set the second destination and rerun the exact Step-1 Python block:

~~~powershell
$projectionB = Join-Path $wave4CacheRoot 'task-8-projection-b.json'
$env:AWX_WAVE4_PROJECTION_PATH = $projectionB
~~~

Then compare:

~~~powershell
$hashA = (Get-FileHash -LiteralPath $projectionA -Algorithm SHA256).Hash.ToLowerInvariant()
$hashB = (Get-FileHash -LiteralPath $projectionB -Algorithm SHA256).Hash.ToLowerInvariant()
$bytesA = (Get-Item -LiteralPath $projectionA).Length
$bytesB = (Get-Item -LiteralPath $projectionB).Length
if ($hashA -ne $hashB -or $bytesA -ne $bytesB) { throw 'semantic-nondeterminism' }
[pscustomobject]@{projectionA=$hashA;projectionB=$hashB;bytes=$bytesA} | Format-List
~~~

Expected: equal hash, byte count, and bytes. Do not prefer the newer output on mismatch.

- [ ] **Step 4: Run final full tests and validators**

~~~powershell
python -B -X utf8 scripts/test_dynamic_rag_quant_audit.py
python -B -X utf8 scripts/test_source_health_scorecard.py
python -B -X utf8 -c "from pathlib import Path; from scripts import dynamic_rag_quant_audit as a; from scripts import source_health_scorecard as s; root=Path('.').resolve(); bundle=a.load_and_validate_current_bundle(root=root); assert s.validate_current_quant_bundle(root)==bundle.metrics; print('finalValidators=PASS')"
~~~

- [ ] **Step 5: Verify canonical Wave-4 bytes and empty history**

Round-trip each of the five nonempty Wave-4 files through the matching production canonical parser and require raw equality. Require progress and journal length 0. Require no entry below Wave-4 proofs and no repair-target-admission file under the Wave-4 proof root.

- [ ] **Step 6: Run privacy and path-leak checks**

Scan only added lines in changed production/test/Gradle/registry files, the five nonempty Wave-4 intake files, final public JSON/NDJSON, duplicate report, and final ignored report. Use repository SECRET_PATTERN and Windows absolute-path patterns. Report counts only; require zero new secret hits and zero absolute-path hits in public/private JSON.

- [ ] **Step 7: Prove wave-1 through wave-3 preservation**

Compare Task-1 registered-wave manifest to final bytes. Require every pre-Wave-4 event ID, active tip, proof pair, intake, progress, and journal hash unchanged. Require:

~~~text
waveCount=4
predecessorRows=17
eventCount=15
activeEvents=15
rejectedFalsePositiveRootCauseGroups=5
verifiedClosedRootCauseGroups=10
Wave-4 events=0
Wave-4 proof pairs=0
Wave-4 target admissions=0
~~~

- [ ] **Step 8: Prove candidate, absent-test, and RagControl preservation**

Require final hashes/states:

~~~text
app/build.gradle.kts
  b1590e63596000c944d6a1e94bf325323d001d41ba8cc9360e3462f1dd4c4597
app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java
  98598256965d3ff9978c763d3639a8d9017062c6a5a7c820d803adbaa1d16b64
main/java/config/RagLightAdapters.java
  9974081bff3e38182efc40cf659176cdc6bf62022496f3ed8cbafd896becfc95
main/java/service/rag/planner/SelfAskPlanner.java
  7c1a82407fa741d5aa7ed5509ddd9d6779a00055f79eccab674af52eb8834786
src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java
  absent
main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java
  d77468cab11c2e7be80c17495d938a05247447f8097c3899464a55101089001e
src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java
  d66bffda959049e8e5b7cb3d2890459c942a02c51b4d4f1aedd7e3dc733e6cfe
~~~

Inspect git diff for those paths and require no hunk introduced by this execution.

- [ ] **Step 9: Inspect final scoped diff and repository gates**

~~~powershell
git status --short
$scope = @('scripts/dynamic_rag_quant_audit.py','scripts/test_dynamic_rag_quant_audit.py','scripts/test_source_health_scorecard.py','build.gradle.kts','verification/structural-repair-waves/registry.json')
git diff --check -- $scope
git diff --stat -- $scope
~~~

The scoped diff check must pass. Also capture the whole-tree git diff --check result and compare it to Task 1; a pre-existing unrelated warning is reported but does not authorize cleanup or convert this lane into a repository-wide HOLD. Inspect every changed hunk plus seven Wave-4 files and separately scan the new files for trailing whitespace. Confirm scripts/source_health_scorecard.py, scripts/harmony_catch_contract.py, app/build.gradle.kts, all Java, older waves, and RagControl did not change through this plan. Require staged count 0, no index lock, no top-level patch, and no source lease.

- [ ] **Step 10: Write final execution report without committing**

Create or amend only:

~~~text
.superpowers/sdd/2026-09-03-wave-four-selfask-reviewed-admission/task-8-report.md
~~~

Use apply_patch. Record:

- approved spec hash and execution-handoff plan hash;
- changed/created paths;
- final registry hash and imported pin equality;
- Stage-A baseline, ledger, metrics, detailed report, manifest, source-set, candidate, and caller-count evidence;
- seven Wave-4 hashes and zero-byte lengths;
- final public hashes;
- exact wave/predecessor/event/active/rejected/verified/eligible/target-gap counters;
- focused/full Python counts and Gradle outcomes;
- projection A/B hashes and byte counts;
- secret and absolute-path counts;
- old-wave preservation;
- candidate, absent-test, and RagControl preservation;
- staged/index-lock/PatchDrop/lease counts;
- repairCreditAdded=0;
- parentGoalComplete=false;
- nextAction=write and approve a separate bounded SelfAsk source-repair design, then run the mandatory three-way source-edit preflight before any application-source mutation.

Do not stage, commit, push, delete the compatibility copy, create the ownership test, create repair progress/proofs/events, or mark the parent goal complete.

## Plan Self-Review Checklist

- The corrected approved spec hash appears in the header and all trust-bearing steps.
- The incorrect AppCleanSourceZombieContractTest premise is excluded; the dedicated future ownership-test path remains absent.
- No application-source mutation occurs; three-way source-edit preflight is deferred to the separately approved repair.
- Task 2 owns strict descriptor-local v3 dispatch and exact SelfAsk decision/evidence fields.
- Intake v1 and exact RetrievalOrderService v2 remain separate and have parity tests.
- The caller helper runs only at Stage-A; historical loading validates frozen count/hash without rescanning live Java.
- Task 3 enforces zero-byte progress/journal, empty proofs, no admission, no event, and zero credit.
- Source-health stays a secondary validator; Task 4 changes fixtures only unless a causal production delegation defect requires a new design decision.
- Tasks 2-5 become fully green under the unchanged three-wave registry.
- Task 6 performs one final pre-registration producer, derives future identities from fresh bytes, freezes five canonical plus two zero-byte files, and validates the unregistered wave.
- Task 7 derives the registry hash, updates registry/pin/Gradle atomically, preserves publication order, and provides a precise rollback boundary.
- Task 8 proves deterministic publication, canonical bytes, privacy, old-wave preservation, candidate/RagControl immutability, and no repair credit.
- No future registry, baseline, issue, group, fingerprint, or artifact hash is guessed; each is measured and constructed before apply_patch.
- No broad proof glob, second publisher, source-health classifier, source-edit lease, commit, stage, push, Browser, Computer, Supabase, provider, or database lane is introduced.
- Type and function names are consistent: ClosureWaveIntake, ClosureWaveDescriptor, ClosureWaveHistory, ClosureHistory, _load_closure_intake, _validate_selfask_reviewed_admission_intake_v3, _count_selfask_compatibility_copy_direct_callers, _load_progress_rows, _load_closure_wave_from_journal_bytes, load_closure_history, and load_and_validate_current_bundle.
