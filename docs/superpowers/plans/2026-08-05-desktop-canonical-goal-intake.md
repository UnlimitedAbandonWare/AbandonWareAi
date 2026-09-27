# Desktop Canonical Goal Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Add one thin repo-local skill and one generated prompt that keep Notebook Y-drive/SMB evidence supporting-only while fixing execution, writes, and final proof to `C:\AbandonWare\demo-1\demo-1\src`; patch application source only after the existing mutation gates permit it.

**Architecture:** The new skill owns only root selection, evidence demotion, lane selection, and a stable output contract. Existing skills continue to own source preflight, source leases, PatchDrop, prompt integration, skill validation, Desktop proof, and Browser/Computer/Supabase evidence. The prompt is registered through the existing manifest and built deterministically.

**Tech Stack:** Markdown, YAML, the existing Python prompt builder, the skill-creator validator, PowerShell repository guards, Gradle only for an independently proven application-source seam.

## Global Constraints

- Treat `st_x (2).txt` as approved design/risk input, not current source truth.
- Keep `Y:\` as `supporting_only`; do not select `YDRIVE_SMB_GUARDED_DIRECT` in this controller.
- Preserve all unrelated dirty files and the foreign `chat-run-identity-v2prime` source lease.
- Do not delete `.git\index.lock`, promote Notebook bundles, change Git trust, stage, commit, push, deploy, or mutate Supabase.
- A 9-hour request is a 540-minute ceiling and not a waiting requirement.

### Task 1: Record RED pressure evidence

**Files:** No repository mutation.

- [x] Run a fresh worker without the new skill.
- [x] Observe that it promoted `Y:\` back to `canonicalWorkspace` and selected `YDRIVE_SMB_GUARDED_DIRECT`.
- [x] Record the desired correction: fixed C execution/read/write/final-proof roots, Y supporting-only, no fallback.

### Task 2: Scaffold and implement the skill

**Files:**

- Create: `.agents/skills/demo1-desktop-canonical-goal-intake/SKILL.md`
- Create: `.agents/skills/demo1-desktop-canonical-goal-intake/agents/openai.yaml`

- [x] Run the official `init_skill.py` scaffold command.
- [x] Replace the scaffold with the thin routing contract.
- [x] Run `quick_validate.py`.
- [x] Run fresh GREEN pressure scenarios and compare with the RED failure.
- [x] Reject invented live booleans by using `evidence_needed` for unobserved fields.

### Task 3: Implement and register the prompt

**Files:**

- Create: `agent-prompts/agents/demo1_desktop_canonical_goal_intake/system_ko.md`
- Create: `agent-prompts/agents/demo1_desktop_canonical_goal_intake/meta.yaml`
- Modify: `agent-prompts/prompts.manifest.yaml`
- Generate: `agent-prompts/out/demo1_desktop_canonical_goal_intake.prompt`

- [x] Confirm all new paths are absent and capture the dirty manifest preimage.
- [x] Add one manifest entry without replacing existing user changes.
- [x] Build only `demo1_desktop_canonical_goal_intake`.
- [x] Parse YAML, reject duplicate IDs, and compare output with the manifest merge result.
- [x] Run count-only prompt secret checks.

### Task 4: Validate the skill family

**Files:** Read-only validation of `.agents/skills/demo1-*` plus the new skill.

- [x] Run the skill-creator validator on the new skill.
- [x] Run the repo skill-family postprocessor with a summary JSON.
- [x] Confirm no TODO markers, invalid links, or duplicate responsibility were introduced.

### Task 5: Re-evaluate application-source mutation

**Files:** None until a clean target is proven.

- [x] Recheck the foreign source lease and `.git\index.lock`.
- [x] Reconfirm active source sets and exact candidate target dirtiness.
- [ ] If and only if the collision gates clear, freeze the exact three-query preflight and acquire the existing Desktop source-owner lease after stable `APPLY`.
- [ ] Implement the smallest TDD patch and run focused-to-broad verification.
- [ ] Otherwise report `evidence_needed` with the single resume gate; do not force a source patch.

### Task 6: Final proof and review

**Files:** All changed prompt/skill artifacts; application source only if Task 5 ran.

- [x] Re-run current file hashes and count-only secret scans.
- [x] Keep Browser, Computer, and Supabase results separate and demand-driven.
- [x] Ask an independent reviewer to verify root invariants, nonduplication, and claims.
- [ ] Mark the long goal complete only if both requested artifact work and any required source work are genuinely closed.
