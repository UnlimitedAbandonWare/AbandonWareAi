# MacSrc Pipeline Sharpening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make MacSrc guard planning root-aware, reuse one deterministic GoalContract score evaluator, and turn the autograder's declared intake decision into a bounded artifact-driven transition tool without authorizing source mutation.

**Architecture:** Extract the already-proven score formula into one side-effect-free PowerShell helper, then consume it from memory and patch postprocessors. Extend the existing defect-intake skill with one `advance_autograder_probe.ps1` state machine whose modes only publish hash-bound handoff artifacts or call the existing intake/session planners. Keep application source, global Git trust, external agents, RED execution, and patch application outside these tools.

**Tech Stack:** Windows PowerShell 5.1-compatible scripts, JSON/SHA-256 artifact contracts, Python `unittest`, repo-local Codex skills.

## Global Constraints

- Work only under `\\DESKTOP-M5NOV6K\MacSrc`; `Y:` is accepted only while `Get-PSDrive Y).DisplayRoot` proves that share.
- Modify repo-local tooling and skill artifacts only; do not touch `main/java`, `main/resources`, `app/**`, Gradle dependencies, DB, Supabase, credentials, OneDrive, commit, push, or deployment state.
- Never run or emit `git config --global`; exact-root SMB Git ownership failure uses `filesystem-cas` evidence.
- Run every behavior through RED -> GREEN; do not weaken a failing assertion to fit implementation.
- Keep all machine artifacts bounded, ready-last, hash-bound, redacted, and `mutationAllowed=false`.
- Do not use subagents for process ceremony; this plan is executed inline because the user requested implementation in the current session.

---

### Task 1: Shared deterministic goal-score evaluator

**Files:**
- Create: `scripts/awx_goal_score_contract.ps1`
- Create: `scripts/demo1_goal_score_contract_tests.ps1`
- Modify: `.agents/skills/demo1-memory-integrity-autopatch/scripts/tri_query_directive_postprocess.ps1`
- Test: `scripts/demo1_memory_integrity_tri_query_contract_tests.ps1`

**Interfaces:**
- Produces: `Measure-AwxGoalScore -Components <IDictionary> -ProvidedScore <double>`, returning `valid`, `computedScore`, and `failureClassification` without writing files.
- Consumes: the ten official component names with numeric values in `0..1`.

- [ ] **Step 1: Write the failing helper contract test**

Add behavior tests that dot-source `scripts/awx_goal_score_contract.ps1`, hand-check the official fixture as `73.5`, reject a component outside `0..1`, and classify a supplied-score mismatch as `goal-score-mismatch`.

- [ ] **Step 2: Run the helper contract test and verify RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_goal_score_contract_tests.ps1`

Expected: FAIL because `scripts/awx_goal_score_contract.ps1` does not exist.

- [ ] **Step 3: Implement the minimal pure evaluator**

Implement only component-name validation, invariant-culture parsing, the repository formula, `0.0..100.0` clamp using floating-point overloads, four-decimal rounding, and optional provided-score comparison with tolerance `0.0001`.

- [ ] **Step 4: Run the helper contract test and verify GREEN**

Run the command from Step 2. Expected: all helper assertions PASS.

- [ ] **Step 5: Replace the embedded memory formula with the helper**

Resolve the repository helper from the current skill script, dot-source it, preserve the existing neutral packet schema and exact failure tokens, and leave candidate publication behavior unchanged.

- [ ] **Step 6: Verify the memory regression suite**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_memory_integrity_tri_query_contract_tests.ps1`

Expected: `pass=36 fail=0` or a higher pass count with zero failures.

### Task 2: Evidence-bound patch postprocess scoring

**Files:**
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/scripts/prepare_patch_tri_query.ps1`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/scripts/new_postprocess_packet.ps1`
- Modify: `scripts/demo1_macsrc_patch_postprocessor_contract_tests.ps1`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/SKILL.md`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/references/postprocess-contract.md`

**Interfaces:**
- Consumes: neutral and final records with `goalScoreComponents` and `goalScoreEvidenceIds`.
- Produces: authoritative `goalScore` from `Measure-AwxGoalScore`; component evidence IDs must be keys in the frozen `subjectInputHashes` map.

- [ ] **Step 1: Add failing score-mismatch and foreign-evidence tests**

Use literal score components that calculate `73.5`. Assert that submitted `70` yields `HOLD` plus `goal-score-mismatch`, and that an evidence ID outside the subject hash keys yields `goal-score-evidence-unbound`.

- [ ] **Step 2: Run the postprocessor contract and verify RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_patch_postprocessor_contract_tests.ps1`

Expected: the new assertions fail because the current finalizer accepts a matching packet/final submitted score without formula or evidence-source validation.

- [ ] **Step 3: Implement minimal validation**

Validate all ten numeric components, require a non-empty evidence-ID list per component, require every ID to be a `subjectInputHashes` key, recompute with the shared helper, classify repairable score failures as HOLD, and publish only the computed score.

- [ ] **Step 4: Run the postprocessor contract and verify GREEN**

Run the command from Step 2. Expected: all assertions PASS and existing terminal-session behavior remains unchanged.

- [ ] **Step 5: Update the skill contract**

Document the added score maps, authoritative computed score, `goal-score-mismatch`, `goal-score-evidence-unbound`, and `RERUN_TRI_QUERY` recovery without duplicating the formula prose.

### Task 3: Deterministic autograder advancement tool

**Files:**
- Create: `.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1`
- Modify: `.agents/skills/demo1-macsrc-defect-intake/scripts/prepare_autograder_probe.ps1`
- Modify: `scripts/demo1_macsrc_defect_intake_contract_tests.ps1`
- Modify: `scripts/test_macsrc_defect_session_skill_family.py`
- Modify: `.agents/skills/demo1-macsrc-defect-intake/SKILL.md`
- Modify: `.agents/skills/demo1-macsrc-defect-intake/references/defect-intent-contract.md`

**Interfaces:**
- `-Mode Finalize`: consumes the ready probe manifest and actual positive/negative/neutral packet files, validates hashes/order/score, and writes `awx.autograder.intake-decision.v1` ready-last.
- `-Mode PromoteIntent`: consumes an APPLY decision plus `awx.autograder.intent-spec.v1`, then invokes the existing `prepare_defect_intent.ps1`; it never runs RED.
- `-Mode PlanSession`: consumes the published intent and separate `awx.red-evidence.v1`, then invokes the existing `new_guard_session_plan.ps1`; it never opens the mutation guard.

- [ ] **Step 1: Add failing Finalize tests**

Create literal packet fixtures. Assert that missing packets, unstable order, score mismatch, foreign evidence IDs, and secret-like packet text fail closed; assert no intent is created by `Finalize`.

- [ ] **Step 2: Add failing promotion/plan tests**

Assert that APPLY without an intent spec cannot create `intent.json`, valid spec creates a PREPARED intent with `nextAction=REPRODUCE_RED`, and missing or nonmatching RED cannot create an actionable guard plan.

- [ ] **Step 3: Run the defect-intake contract and verify RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_defect_intake_contract_tests.ps1`

Expected: FAIL because `advance_autograder_probe.ps1` does not exist and dispatch stage 3 has no executable command.

- [ ] **Step 4: Implement Finalize minimally**

Reuse the frozen manifest, real packet SHA-256 values, exact P/N/Neutral schemas, A-B/B-A stability, the shared score helper, count-only secret detection, atomic JSON publication, SHA sidecar, and ready marker. Do not invoke agents or shell commands.

- [ ] **Step 5: Implement PromoteIntent and PlanSession minimally**

Validate predecessor sidecar/ready hashes and immutable intent-spec fields. Forward normalized fields to the existing intake helper. In PlanSession, forward only the existing intent and RED relative paths to the existing planner. Refuse overwrite and preserve one next action.

- [ ] **Step 6: Run defect-intake and family tests**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_defect_intake_contract_tests.ps1
python -X utf8 scripts\test_macsrc_defect_session_skill_family.py
```

Expected: zero failures; discovery test finds the advancement tool.

- [ ] **Step 7: Update the intake skill and reference**

Document the three modes, exact schemas, bounded mutation surface, no arbitrary command execution, and the hard stop before RED/guard mutation.

### Task 4: Root-accurate guarded session planning with SMB CAS

**Files:**
- Modify: `.agents/skills/demo1-macsrc-guarded-patch-session/scripts/new_guard_session_plan.ps1`
- Modify: `scripts/demo1_macsrc_guarded_patch_session_contract_tests.ps1`
- Modify: `.agents/skills/demo1-macsrc-guarded-patch-session/SKILL.md`
- Modify: `.agents/skills/demo1-macsrc-guarded-patch-session/references/session-contract.md`

**Interfaces:**
- Produces: plan fields `rootIdentity`, `gitEvidenceMode`, and `globalSafeDirectoryMutation=false`.
- Root policy: actionable READY only for canonical `\\desktop-m5nov6k\MacSrc`; nonproduction roots return `HOLD/macsrc-root-mismatch`.

- [ ] **Step 1: Change the fixture expectation and verify RED**

Replace the existing temp-root READY assertion with `HOLD/macsrc-root-mismatch`. Add an exact-root conditional integration fixture showing dubious Git records `filesystem-cas` without becoming an ownership failure.

- [ ] **Step 2: Run the guarded-session contract and verify RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_guarded_patch_session_contract_tests.ps1`

Expected: the temp-root assertion fails because current code emits `READY_FOR_GUARD`.

- [ ] **Step 3: Implement the root and Git evidence policy**

Add the exact production root constant, compare canonical roots after mapped-drive normalization, add `macsrc-root-mismatch` before verdict selection, and map dubious Git to `filesystem-cas` only on the exact production root. Never execute Git configuration commands.

- [ ] **Step 4: Run the guarded-session contract and verify GREEN**

Run the command from Step 2. Expected: all assertions PASS; temporary roots never emit actionable READY.

- [ ] **Step 5: Update the session skill contract**

Document the narrow exact-root CAS exception and retain fail-closed handling for index locks, queue/lease collisions, reparse traversal, and preimage drift.

### Task 5: Parse, validate, and postprocess the whole skill family

**Files:**
- Verify all files modified in Tasks 1-4.

**Interfaces:**
- Produces: compact verification evidence only; no application-source or external-lane mutation.

- [ ] **Step 1: Parse every changed PowerShell file**

Use `[System.Management.Automation.Language.Parser]::ParseFile` and fail if any parse error count is nonzero.

- [ ] **Step 2: Run all focused contract suites**

Run the five PowerShell suites from Tasks 1-4 plus `python -X utf8 scripts\test_macsrc_defect_session_skill_family.py`.

- [ ] **Step 3: Validate each changed skill**

Run `quick_validate.py` against memory-integrity, defect-intake, guarded-session, and patch-postprocessor skills.

- [ ] **Step 4: Run skill-family postprocessing**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
```

Expected: no blocking validation, discovery, scaffold, secret, or lane-coverage issue attributable to these changes.

- [ ] **Step 5: Verify forbidden surfaces**

Confirm `git config --global` occurrence count is zero in changed scripts, application-source mutation count is zero, top-level PatchDrop count is unchanged, no source lease remains, and Supabase mutation is false.

- [ ] **Step 6: Self-review against this plan**

Check every target interface and failure token, scan the plan for placeholder language, and report any unimplemented item as `evidence_needed` rather than claiming completion.

