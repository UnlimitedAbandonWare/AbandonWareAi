# MacSrc Defect Session Skill Family Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify three repo-local skills that standardize defect intake, guarded MacSrc patch sessions, and fixed-seed postprocessing.

**Architecture:** Keep the existing checksum/lease guard and memory-integrity tools authoritative. Add one small deterministic helper only where an artifact shape must be machine-enforced, and keep each skill independently testable before creating the next.

**Tech Stack:** PowerShell 5.1, UTF-8 JSON/Markdown, Codex Agent Skills, existing MacSrc guard/scanner/tri-query scripts.

## Global Constraints

- Application source mutation is out of scope for this implementation.
- All created artifacts remain under `\\desktop-m5nov6k\MacSrc`.
- OneDrive is not a source or evidence destination.
- Supabase is project-scoped read-only evidence only; no SQL or project mutation.
- Every script test must be observed RED before implementation and GREEN after.
- Each skill must pass pressure testing and `quick_validate.py` before the next skill is created.
- Existing dirty files outside the listed paths belong to the user and must remain untouched.

---

### Task 1: Defect Intake Skill

**Files:**
- Create: `scripts/demo1_macsrc_defect_intake_contract_tests.ps1`
- Create: `.agents/skills/demo1-macsrc-defect-intake/SKILL.md`
- Create: `.agents/skills/demo1-macsrc-defect-intake/agents/openai.yaml`
- Create: `.agents/skills/demo1-macsrc-defect-intake/scripts/prepare_defect_intent.ps1`
- Create: `.agents/skills/demo1-macsrc-defect-intake/references/defect-intent-contract.md`
- Create: `agent-prompts/agents/demo1_macsrc_defect_intake/system_ko.md`

**Interfaces:**
- Consumes: defect class, evidence IDs, relative targets, watch roots, boundary files, RED/GREEN commands.
- Produces: immutable `PatchIntent` JSON with `mutationAllowed=false`, normalized relative paths, evidence hashes, and `intentSha256` sidecar output.

- [x] **Step 1: Write the failing behavior test**

```powershell
& $sut -Root $tempRoot -RunId "intake-red-001" `
  -DefectClass "silent-catch" -EvidenceIds "E1" `
  -TargetFiles "main/java/example/Foo.java" -WatchRoots "main/java/example" `
  -BoundaryEvidenceFiles "settings.gradle" `
  -RedCommand ".\gradlew.bat test --tests FooTest" `
  -ExpectedRedSignal "expected failure" `
  -GreenCommand ".\gradlew.bat test --tests FooTest"
$intent = Get-Content $output -Raw | ConvertFrom-Json
Assert-Equal $intent.mutationAllowed $false "intake never mutates"
Assert-Equal $intent.stage "PREPARED" "stage is machine readable"
```

- [x] **Step 2: Run RED and confirm the helper is missing**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_macsrc_defect_intake_contract_tests.ps1`

Expected: nonzero exit with `prepare_defect_intent.ps1 is missing`.

- [x] **Step 3: Initialize and implement the minimal skill**

Run `init_skill.py demo1-macsrc-defect-intake --path .agents/skills --resources scripts,references` with explicit `display_name`, `short_description`, and `$demo1-macsrc-defect-intake` default prompt. Replace scaffold markers. Implement path normalization, boundary file hashing, secret-pattern rejection, atomic temp-to-final publication, and existing-intent immutability.

- [x] **Step 4: Run GREEN and forward pressure test**

Run the contract test, `quick_validate.py`, and the same no-RED/sourceSet pressure scenario with the skill loaded. Expected: `intent-not-ready`, no application mutation, and a fixed `PatchIntent` shape.

### Task 2: Guarded Patch Session Skill

**Files:**
- Create: `scripts/demo1_macsrc_guarded_patch_session_contract_tests.ps1`
- Create: `.agents/skills/demo1-macsrc-guarded-patch-session/SKILL.md`
- Create: `.agents/skills/demo1-macsrc-guarded-patch-session/agents/openai.yaml`
- Create: `.agents/skills/demo1-macsrc-guarded-patch-session/scripts/new_guard_session_plan.ps1`
- Create: `.agents/skills/demo1-macsrc-guarded-patch-session/references/session-contract.md`
- Create: `agent-prompts/agents/demo1_macsrc_guarded_patch_session/system_ko.md`

**Interfaces:**
- Consumes: one validated `PatchIntent`, hash-bound RED evidence, and the existing MacSrc guard state.
- Produces: a non-mutating `GuardSessionPlan` with exact phase decisions; it delegates every mutation check to `demo1-macsrc-smb-direct-patch`.

- [x] **Step 1: Write the failing contract test**

```powershell
$result = Invoke-PressureScenario -IntentWithoutRed $fixture `
  -PendingRenameOverlap $true
Assert-Equal $result.verdict "HOLD" "RED and queue gates are mandatory"
Assert-Equal $result.mutationPerformed $false "no guarded mutation on HOLD"
Assert-Equal $result.requiredHashes.Count 3 "intent, session, and postimage bind"
```

- [x] **Step 2: Run RED against the absent skill**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_macsrc_guarded_patch_session_contract_tests.ps1`

Expected: nonzero exit with `guarded patch session skill is missing`.

- [x] **Step 3: Initialize and implement the minimal orchestration skill**

Initialize with `init_skill.py`. Keep the body concise and put exact Prepare,
Verify, RED, apply_patch, GREEN, Complete, and Abort commands in
`references/session-contract.md`. Require the existing direct-patch skill and
do not add a second guard implementation.

- [x] **Step 4: Run GREEN and forward pressure test**

Run the contract test and `quick_validate.py`, then repeat the pending rename
scenario with the skill loaded. Expected: no overlap acknowledgement, no
mutation, and exact `red-not-reproduced` plus `patch-drop-pending` reasons.

### Task 3: Patch Postprocessor Skill

**Files:**
- Create: `scripts/demo1_macsrc_patch_postprocessor_contract_tests.ps1`
- Create: `.agents/skills/demo1-macsrc-patch-postprocessor/SKILL.md`
- Create: `.agents/skills/demo1-macsrc-patch-postprocessor/agents/openai.yaml`
- Create: `.agents/skills/demo1-macsrc-patch-postprocessor/scripts/new_postprocess_packet.ps1`
- Create: `.agents/skills/demo1-macsrc-patch-postprocessor/references/postprocess-contract.md`
- Create: `agent-prompts/agents/demo1_macsrc_patch_postprocess/system_ko.md`

**Interfaces:**
- Consumes: intent, guard session, terminal outcome, verification record, before/after integrity ledgers, tri-query decision, and optional Supabase evidence metadata.
- Produces: non-mutating `PostprocessPacket` with paired metrics, ordered adjudication state, Supabase classification, and one next action.

- [x] **Step 1: Write the failing behavior test**

```powershell
& $sut -Root $tempRoot -RunId "post-red-001" `
  -IntentFile $intent -SessionFile $session -VerificationFile $verification `
  -BeforeIntegrityFile $before -AfterIntegrityFile $after
$packet = Get-Content $output -Raw | ConvertFrom-Json
Assert-Equal $packet.verdict "HOLD" "missing postimage hash fails closed"
Assert-Contains $packet.failureClasses "paired-seed-mismatch"
Assert-Equal $packet.nextMutationAllowed $false "postprocess never chains mutation"
```

- [x] **Step 2: Run RED and confirm the helper is missing**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_macsrc_patch_postprocessor_contract_tests.ps1`

Expected: nonzero exit with `new_postprocess_packet.ps1 is missing`.

- [x] **Step 3: Initialize and implement the minimal skill**

Initialize with `init_skill.py`. Implement hash validation, same-seed checks,
postimage coverage checks, A-B/B-A order-stability fields, demand-driven
Supabase read-only classification, secret-safe atomic output, and
`nextMutationAllowed=false` for every verdict.

- [x] **Step 4: Run GREEN and forward pressure test**

Run the contract test and `quick_validate.py`, then repeat the incomplete
verification/different-seed/missing-Supabase-scope scenario. Expected: `HOLD`,
three explicit failure classes, and no automatic next patch.

### Task 4: Prompt Pack and Family Postprocessing

**Files:**
- Modify: `agent-prompts/prompts.manifest.yaml`
- Create: `data/agent-handoff/skill-tdd/macsrc-defect-session-20260729/baseline.json`
- Create: `data/agent-handoff/skill-tdd/macsrc-defect-session-20260729/verification.json`

**Interfaces:**
- Consumes: the three independently GREEN skills.
- Produces: registered Korean prompt entries and compact validation evidence.

- [x] **Step 1: Add a failing prompt/family registration test**

Extend a new family test so all three prompt IDs and skill paths are required,
then run it before manifest registration. Expected: nonzero exit naming the
missing IDs.

- [x] **Step 2: Register the three prompt templates**

Add `demo1_macsrc_defect_intake`, `demo1_macsrc_guarded_patch_session`, and
`demo1_macsrc_patch_postprocess` entries with UTF-8 output paths.

- [x] **Step 3: Run the complete verification matrix**

Run all three contract tests, all three `quick_validate.py` commands, prompt
manifest validation, `validate_demo1_skill_family.ps1 -DiscoverPrefix demo1-
-SummaryJson`, scaffold/secret scans, and targeted `git diff --check`.

- [x] **Step 4: Persist evidence and self-review**

Record commands, exit codes, assertion counts, hashes, baseline gaps, Supabase
`evidence_needed`, unchanged application-source surface, and Desktop final-proof
status. Scan this plan for placeholders and verify all paths and names match.

## Task 5: Adversarial Hardening Review

- [x] Dispatch independent intake/session, postprocess, and contract-gap audits.
- [x] RED/GREEN handoff-only output and junction escape rejection.
- [x] RED/GREEN locked RED snapshot and directory lease detection.
- [x] RED/GREEN verification SHA, root, changed-count, and no-op binding.
- [x] RED/GREEN real three-packet subject binding and staged dispatch setup.
- [x] RED/GREEN non-mutating autograder probe dispatch hook.
- [x] Downgrade Supabase metadata to untrusted support and keep runtime lineage
  on HOLD.
