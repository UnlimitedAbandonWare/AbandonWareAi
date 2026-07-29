# Codex Global Instructions Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the oversized demo-1 task in global Codex instructions with compact Windows defaults while keeping the full task and independent review contract in the repository.

**Architecture:** Store a versioned global-instructions template under `agent-prompts`, validate it together with the canonical long prompt, and synchronize the validated template to `%USERPROFILE%\.codex\AGENTS.md`. Add only one project-level pointer so global, project, and task responsibilities remain separate.

**Tech Stack:** Markdown, Python `unittest`, PowerShell, Git, Codex `AGENTS.md` discovery.

## Global Constraints

- The global instructions file is at most 3,072 UTF-8 bytes.
- Global plus project `AGENTS.md` is below 32,768 bytes.
- The global file contains no `/goal`, demo-1 absolute path, nine-hour workflow, fixed source snapshot, or mandatory report template.
- Existing user changes in project `AGENTS.md` are preserved.
- Review artifacts contain counts, hashes, reason codes, and command evidence only.
- No raw prompts, provider responses, credentials, authorization headers, or full error bodies are stored.
- Browser, Computer, Supabase, and multi-agent work remain demand-driven.

---

### Task 1: Add RED instruction-scope contracts

**Files:**
- Modify: `scripts/test_smb_decommission_usage_prompt.py`
- Test: `scripts/test_smb_decommission_usage_prompt.py`

**Interfaces:**
- Consumes: `agent-prompts/codex_global_windows_personal_instructions.md`, root `AGENTS.md`, and the canonical long prompt.
- Produces: `audit_global_instructions(global_text: str, project_text: str) -> list[str]` and expanded canonical prompt markers.

- [ ] **Step 1: Write the failing tests**

Add paths for the global template and project instructions. Require the global
template to be at most 3,072 bytes, the combined template and project file to
be below 32,768 bytes, and reject `/goal`, `C:\AbandonWare`, `9시간`, and
`Return exactly this structure`. Require `SUPPORT_CONTRACT`,
`SUPPORT_SCENARIO`, `FALSIFY`, `NEUTRAL`, and `APPLY | HOLD | REJECT` in the
canonical prompt.

- [ ] **Step 2: Run the test to verify RED**

Run:

```powershell
python -X utf8 scripts\test_smb_decommission_usage_prompt.py
```

Expected: FAIL because the global template does not exist and the canonical
prompt lacks the four explicit review markers.

- [ ] **Step 3: Keep the failure focused**

Confirm the failure names only missing global-template/review-contract
requirements; do not modify Java, Gradle, runtime, Supabase, or UI source.

### Task 2: Implement the scoped Markdown artifacts

**Files:**
- Create: `agent-prompts/codex_global_windows_personal_instructions.md`
- Modify: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: contracts from Task 1.
- Produces: compact global template, one project pointer, and the independent review packet protocol.

- [ ] **Step 1: Create the compact global template**

Use only these durable sections: Windows environment, working agreements,
scope/cost control, evidence/privacy, and independent review. Keep task-specific
paths and commands out.

- [ ] **Step 2: Add one project prompt pointer**

Immediately after the existing rule that long execution prompts belong under
`agent-prompts`, add a single bullet naming
`agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md` as the
manual Desktop-only SMB/Codex-usage pass. Do not change neighboring user edits.

- [ ] **Step 3: Add the four-packet review contract**

Add a bounded subsection after Lane F specifying:

```text
SUPPORT_CONTRACT -> independent schema/count/hash validation
SUPPORT_SCENARIO -> independent adverse Korean/English scenario validation
FALSIFY -> false-green and mixed-correlation search
NEUTRAL -> APPLY | HOLD | REJECT without majority voting
```

The subsection must forbid raw prompt/response storage and prevent a positive
provider claim when provider/wire attempt evidence is absent.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run:

```powershell
python -X utf8 scripts\test_smb_decommission_usage_prompt.py
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

Expected: all focused contracts pass and the secret-pattern test reports no
new secret leakage.

### Task 3: Synchronize and prove the Windows global file

**Files:**
- Replace: `C:\Users\nninn\.codex\AGENTS.md`
- Verify: `agent-prompts/codex_global_windows_personal_instructions.md`

**Interfaces:**
- Consumes: validated template from Task 2.
- Produces: byte-identical active global instructions outside Git.

- [ ] **Step 1: Replace the global file with the validated template content**

Use `apply_patch` so the persistent edit is explicit. Do not change
`config.toml` or create `AGENTS.override.md`.

- [ ] **Step 2: Verify hashes and instruction capacity**

Run a PowerShell check that prints only paths, sizes, SHA-256 values, forbidden
marker counts, and the combined global/project byte count. Expected:

```text
globalBytes <= 3072
globalHash == templateHash
combinedBytes < 32768
forbiddenMarkerCount == 0
```

- [ ] **Step 3: Run the full focused test set again**

Run:

```powershell
python -X utf8 scripts\test_smb_decommission_usage_prompt.py
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
git diff --check -- AGENTS.md agent-prompts scripts/test_smb_decommission_usage_prompt.py
```

Expected: tests pass and `git diff --check` emits no error.

- [ ] **Step 4: Commit only owned repository files**

Stage and commit the test, template, canonical prompt, project pointer, and this
plan. Do not stage unrelated dirty files. The global file remains an external
personal setting and is proven by its hash rather than Git.

- [ ] **Step 5: Reload boundary**

Report that the current task retains its already-loaded instruction chain.
Verify the new value in Settings if UI control is available, then require a new
Codex task or app restart for behavioral proof.
