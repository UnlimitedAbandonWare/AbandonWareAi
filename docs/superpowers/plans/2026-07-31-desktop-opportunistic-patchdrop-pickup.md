# Desktop Opportunistic PatchDrop Pickup Implementation Plan

**Status:** Superseded. Continue only with
`docs/superpowers/plans/2026-07-31-desktop-patchdrop-autoconsume-implementation.md`.
The hook-only implementation below is retained for audit history and must not
be executed.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing project hook so the next ordinary Desktop command detects a Notebook PatchDrop bundle and routes it through the existing Desktop-owned audit and apply gates.

**Architecture:** Add pure, read-only candidate discovery and context composition functions to the existing `UserPromptSubmit` classifier. The hook never mutates; it emits a bounded routing packet that requires the existing Desktop dispatch integrity, producer command hash, promotion, lease, janitor, and verification gates.

**Tech Stack:** PowerShell 5.1-compatible scripts, JSON v3 PatchDrop manifests, existing Codex `UserPromptSubmit` hook, existing AWX Control Tower and janitor scripts.

## Global Constraints

- Canonical Notebook workspace remains `Y:\`; never expose the backing UNC.
- Keep `.codex/hooks.json` byte-for-byte unchanged.
- No background watcher, service, scheduled task, direct `git apply`, or second mutation protocol.
- A Notebook artifact is a detection signal only; Desktop dispatch SHA and `producerCommandHash` are required preauthorization evidence.
- The hook is read-only, fail-closed, and emits at most 512 UTF-8 bytes of `additionalContext`.
- Do not modify application source, Gradle configuration, provider configuration, database surfaces, credentials, or public APIs.
- Use the existing shared lease and compare-and-swap guard for the two declared repository writes.
- Do not commit, push, deploy, or send external messages; those operations were not authorized.
- Desktop final hook and runtime proof remains `evidence_needed`.

---

## File Structure

- `.codex/hooks/source_edit_triage.ps1` — retains source-edit classification and adds pure Notebook PatchDrop discovery plus bounded context composition.
- `scripts/patchdrop_opportunistic_pickup_contract_tests.ps1` — constructs real temporary filesystem fixtures and asserts observable detector and hook-output behavior.
- `docs/superpowers/specs/2026-07-31-desktop-opportunistic-patchdrop-pickup-design.md` — approved design and authority boundary.
- `docs/superpowers/plans/2026-07-31-desktop-opportunistic-patchdrop-pickup.md` — this implementation plan.

### Task 1: Add the failing detector contract

**Files:**
- Create: `scripts/patchdrop_opportunistic_pickup_contract_tests.ps1`
- Read: `.codex/hooks/source_edit_triage.ps1`

**Interfaces:**
- Consumes: `Get-NotebookPatchDropPickupContext -Root <path>` and `New-SourceEditHookOutput -Prompt <text> -PickupContext <text>`.
- Produces: a standalone exit-code contract suite whose RED is a missing detector command.

- [ ] **Step 1: Write the fixture helper and failing behavior tests**

Create a temporary root containing `__patch_drop__/notebook` and
`__patch_drop__/dispatch`. Dot-source the production classifier with
`-LibraryMode`. Use a helper with this contract:

```powershell
function New-NotebookBundleFixture {
    param([string]$Root, [string]$Topic, [switch]$Dispatch, [switch]$Malformed)
    # Write the six nested producer-v3 artifacts. For a normal fixture, write a
    # manifest with node=notebook, activePatch=<topic>-notebook-v3.patch,
    # desktopFinalProof=evidence_needed, and local-worktree sourceIsolation.
    # When -Dispatch is present, write the three exact dispatch artifact names.
    # When -Malformed is present, replace the manifest with invalid JSON.
}
```

Assert these literal outcomes:

```text
empty root -> empty context
one valid fixture plus dispatch -> contains triggerReason=notebook-patchdrop-detected and the exact topic
malformed fixture -> contains triggerReason=notebook-patchdrop-invalid and HOLD
two valid fixtures -> contains triggerReason=notebook-patchdrop-ambiguous and candidateCount=2
one valid fixture plus matching top-level patch -> contains triggerReason=notebook-patchdrop-promoted
one valid fixture plus unrelated top-level patch -> contains active-top-level-exists and HOLD
combined source-edit plus pickup output -> valid UserPromptSubmit JSON with additionalContext <= 512 UTF-8 bytes
all detector runs -> fixture file hashes and counts are unchanged
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\patchdrop_opportunistic_pickup_contract_tests.ps1
```

Expected: non-zero exit with a failure identifying that
`Get-NotebookPatchDropPickupContext` is not defined. This is the required RED;
an unrelated parse or fixture error must be fixed before continuing.

### Task 2: Implement read-only discovery and hook composition

**Files:**
- Modify: `.codex/hooks/source_edit_triage.ps1`
- Test: `scripts/patchdrop_opportunistic_pickup_contract_tests.ps1`

**Interfaces:**
- Consumes: a repository root containing the existing PatchDrop layout and the parsed user prompt.
- Produces: `Get-NotebookPatchDropPickupContext -Root <path>` returning `''` or one bounded routing string, plus one compact hook JSON document when source-edit intent or pickup context exists.

- [ ] **Step 1: Prepare and verify the repository-owned shared-write guard**

After Task 1 has produced the expected RED, start a unique guarded session.
Declare the now-existing contract test and the classifier as targets, use
`.codex/hooks.json`, `AGENTS.md`, and `scripts/awx_mcp_toolbox.py` as boundary
evidence, and watch `.codex/hooks` plus `scripts`. Run `Prepare`, then run
`Verify` immediately before the production `apply_patch`. Keep the lease until
Task 3 completes or aborts with restored preimages.

- [ ] **Step 2: Add bounded discovery constants and safe helpers**

Add constants for maximum topic length 64 and maximum additional-context size
512. Add a SHA helper only for test-side mutation comparison if the production
algorithm needs no hashes; do not compute or emit patch bodies.

- [ ] **Step 3: Implement candidate parsing**

Implement this exact decision shape:

```powershell
function Get-NotebookPatchDropPickupContext {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][string]$Root)

    # Resolve root, reject reparse PatchDrop/notebook/dispatch directories, and
    # enumerate only *-notebook-v3.manifest.json without timestamp ordering.
    # Validate filename/topic agreement, schema, node, activePatch,
    # desktopFinalProof, local-worktree isolation booleans and hashes, the six
    # nested artifact names, and the three Desktop dispatch artifact names.
    # Return empty for zero manifests; invalid/HOLD for any invalid manifest;
    # ambiguous/HOLD for valid count != 1; detected for zero top-level patches;
    # promoted for exactly one matching top-level patch; otherwise
    # active-top-level-exists/HOLD.
}
```

The function catches filesystem and JSON errors and returns an invalid/HOLD
context rather than throwing. It never creates, modifies, moves, or removes a
file.

- [ ] **Step 4: Extend the hook output API**

Change the output function to:

```powershell
function New-SourceEditHookOutput {
    [CmdletBinding()]
    param(
        [AllowEmptyString()][string]$Prompt,
        [AllowEmptyString()][string]$PickupContext = ''
    )
    # Append the shortened source-edit context when intent matches and append
    # PickupContext when non-empty. Return empty only when both are empty.
    # Reject output whose additionalContext exceeds 512 UTF-8 bytes or whose
    # whole JSON exceeds the existing 1024-byte limit.
}
```

- [ ] **Step 5: Invoke discovery from the real hook**

Derive the repository root from the script location (`.codex/hooks` → repo
root), call `Get-NotebookPatchDropPickupContext`, and pass its result into
`New-SourceEditHookOutput`. Preserve the existing fail-soft outer `try/catch` and
exit code `0`; hook failure never grants mutation authority.

- [ ] **Step 6: Run the focused test and verify the first GREEN**

Run the Task 1 command. Expected: exit `0`; source-edit-only, pickup-only, and
combined contexts all remain valid and bounded, and every fixture mutation
check reports zero changed files.

### Task 3: Guard completion and regression verification

**Files:**
- Modify: `.codex/hooks/source_edit_triage.ps1`
- Create: `scripts/patchdrop_opportunistic_pickup_contract_tests.ps1`
- Verify unchanged: `.codex/hooks.json`

**Interfaces:**
- Consumes: the frozen preimages and repository-owned source-edit lease.
- Produces: postimage-bound verification evidence and a released lease.

- [ ] **Step 1: Run focused and regression commands**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\patchdrop_opportunistic_pickup_contract_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: both exit `0`. Parse `.codex/hooks.json` and verify its SHA-256 remains
`68490D373586A60C9CA0FF5C31319602C13FE87E5AFC277DD111A4FBB182A357`.
Run a count-only high-confidence secret scan over the two changed files;
expected count `0`.

- [ ] **Step 2: Complete the guard**

Write the guard's verification JSON with the real commands, exit code, immutable
session hash, and both target postimage hashes. Run `Complete`; expected lease
release, no undeclared watched writes, and verified postimage evidence.

- [ ] **Step 3: Record final limitations**

Report Notebook proof as supporting evidence. Set
`runtimeLineageVerdict=HOLD` and `desktopFinalProof=evidence_needed` until a fresh
Desktop command observes the hook context and the existing Desktop audit/apply
gate completes.

## Self-Review

- Spec coverage: detection, dispatch-bound authority, no timestamp selection,
  queue ambiguity, output bounds, no hook mutation, regression, rollback, and
  Desktop final proof are each assigned to a task.
- Placeholder scan: no deferred implementation markers or unspecified tests
  remain.
- Type consistency: the detector returns a string; the output composer accepts
  the same string; both limits use UTF-8 byte counts.
- Scope: one existing production script and one new contract test; hook config
  and all application source remain unchanged.
