# Desktop PatchDrop Auto-Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Desktop-owned, fail-closed watcher package that automatically consumes only new, validated Notebook PatchDrop v3 bundles after Desktop enrollment.

**Architecture:** A pure PowerShell contract validates policy and selects candidates. A one-shot runner composes existing promotion, lease, janitor-apply, verification, and rollback gates. A Desktop-only setup script copies immutable local runner files and registers a periodic current-user Scheduled Task.

**Tech Stack:** Windows PowerShell 5.1, Task Scheduler cmdlets, Git, existing PatchDrop v3 janitor scripts, Gradle wrapper.

## Global Constraints

- Canonical Notebook workspace remains `Y:\`; raw backing paths are never printed.
- Desktop canonical ownership and final proof remain Desktop-only.
- Existing producer artifacts at installation are baseline-only.
- No hidden hooks, profiles, prompt interception, remote Task Scheduler calls, DB/provider/credential mutation, or application-source changes.
- Exactly one new Notebook bundle may be processed per invocation.
- Tool hash drift, ambiguous queues, dirty target overlap, index lock, lease conflict, secret hit, checksum failure, or failed rollback stops intake.
- Do not commit, push, deploy, or send external messages in this session.

---

### Task 1: Pure intake contract

**Files:**
- Create: `__patch_drop__/desktop_autointake_tests.ps1`
- Create: `__patch_drop__/desktop_autointake_contract.ps1`

**Interfaces:**
- Produces: `Get-AwxFileSha256`, `Get-AwxNotebookBundleInventory`, `Read-AwxAutoIntakePolicy`, `Select-AwxAutoIntakeCandidate`, `Test-AwxPinnedToolHashes`, `New-AwxAutoIntakeTaskPlan`.

- [x] Write tests for stable hashes, baseline exclusion, one-candidate selection, multi-candidate blocking, malformed policy, and tool-hash drift.
- [x] Run `desktop_autointake_tests.ps1`; confirm failure because the contract file is absent.
- [x] Implement only the pure functions required by those tests.
- [x] Run the test suite and confirm all Task 1 cases pass.

### Task 2: Read-only setup plan

**Files:**
- Modify: `__patch_drop__/desktop_autointake_tests.ps1`
- Create: `__patch_drop__/desktop_autointake_setup.ps1`

**Interfaces:**
- Consumes: `New-AwxAutoIntakeTaskPlan`, `Get-AwxNotebookBundleInventory`.
- Produces: `-Action Plan|Install|Status|Uninstall`; Plan has no filesystem or Task Scheduler mutation.

- [x] Add failing tests proving Plan is deterministic and does not register a task or write a policy.
- [x] Run the focused tests and confirm the missing setup script is the failure.
- [x] Implement Plan and Desktop-only Install/Status/Uninstall gates; keep Install unreachable from mapped/UNC roots.
- [x] Run the focused tests and confirm Plan behavior passes.

### Task 3: One-shot runner planning gate

**Files:**
- Modify: `__patch_drop__/desktop_autointake_tests.ps1`
- Create: `__patch_drop__/desktop_autointake_once.ps1`

**Interfaces:**
- Consumes: Desktop-local policy, pure contract functions, pinned hashes for `janitor_promote_producer_pending.ps1`, `source_edit_session.ps1`, `source_edit_lease_contract.ps1`, and `janitor_apply_one.ps1`.
- Produces: bounded status lines and exit codes; `-PlanOnly` never mutates PatchDrop or source.

- [x] Add failing tests for zero candidates, exactly one candidate, multiple candidates, and pinned tool drift in PlanOnly mode.
- [x] Run the focused tests and confirm the missing runner is the failure.
- [x] Implement the PlanOnly path, process lock, root/policy/tool gates, and candidate decision.
- [x] Run the focused tests and confirm PlanOnly cases pass.

### Task 4: Desktop apply orchestration

**Files:**
- Modify: `__patch_drop__/desktop_autointake_once.ps1`
- Modify: `__patch_drop__/desktop_autointake_tests.ps1`

**Interfaces:**
- Consumes: existing promotion, lease, and janitor scripts plus fixed Gradle commands.
- Produces: applied/rejected outcome and Desktop-local processed fingerprint ledger.

- [x] Add a temporary-root failing integration test that proves mutation mode is blocked outside Desktop or verified test mode.
- [x] Implement promotion, lease acquisition, immutable patch snapshot, apply, fixed verification ladder, reverse check, rollback, bundle movement, and ledger update.
- [x] Run the temporary-root tests; confirm no real source or live PatchDrop queue is changed.

### Task 5: Verification and handoff

**Files:**
- Verify: all four new files plus design and plan documents.

**Interfaces:**
- Produces: Notebook supporting evidence and a Desktop installation directive; Desktop final proof remains `evidence_needed`.

- [x] Run `desktop_autointake_tests.ps1`.
- [x] Run `janitor_tests.ps1 -Suite CoreGuards`.
- [x] Run `janitor_inventory.ps1` and confirm live top-level patches remain zero.
- [x] Run `awx_mcp_completion_audit.py --root .` and classify unrelated pre-existing findings separately.
- [x] Run `git diff --check` on the declared files and a count-only secret scan.
- [x] Record Desktop-required verification without installing a task from Notebook.

## Observed Verification

- Auto-intake TDD suite: `54 passed, 0 failed`.
- Existing janitor CoreGuards: four pre-existing fixture failures because the
  suite attempts its first source-lease probe before initializing the temporary
  directory as a Git repository; the remaining emitted guard cases passed.
- Completion audit: incomplete because of unrelated stale/missing repository
  evidence artifacts; its count-only secret result remained zero.
- Live top-level PatchDrop queue remained empty. One pre-existing active source
  lease was observed and left untouched.
- Desktop Scheduled Task installation and Desktop Gradle proof remain
  `evidence_needed`; the Notebook only produced and verified the setup package.
