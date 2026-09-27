# Notebook-to-Desktop Tooling Auto-Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This repository does not authorize commits for this task, so each task ends with a hash/status checkpoint instead of a commit.

**Goal:** Apply exactly one eligible Notebook tooling PatchDrop v3 bundle during the Desktop's normal non-status `goal_next` command, with deterministic discovery, Desktop-only authority, focused verification, and rollback.

**Architecture:** A pure PowerShell library parses ready markers and Git patch targets without mutation. A bounded orchestrator reuses the existing PatchDrop promotion, desktop-consumer lease, and janitor apply scripts, derives focused test commands from changed test paths, and reverses a failed patch before releasing the lease. The existing `goal_next.ps1` wrapper calls the orchestrator before `-EnsureFresh`, while `-Status`, Notebook, mapped-drive, application-source, and ambiguous-queue paths remain non-mutating.

**Tech Stack:** PowerShell 5.1-compatible scripts, Git patch format, existing PatchDrop v3 JSON/SHA sidecars, Python/pytest for changed Python tests.

## Global Constraints

- Notebook canonical workspace remains `Y:\`; never print or persist its raw backing path.
- Desktop canonical ownership and final verification remain authoritative.
- Automatic mutation is restricted to tooling, tests, skills, and bounded evidence; `main/`, `app/`, Gradle/build configuration, DB/DDL, credentials, deployment, and PatchDrop trust-boundary scripts are prohibited.
- `scripts/goal_next.ps1 -Status` remains read-only.
- Do not add a daemon, scheduled task, registry entry, shell-profile hook, background watcher, external message, DB mutation, commit, push, or deployment.
- Reuse the existing producer, promotion, source lease, and janitor apply contracts; do not create a second PatchDrop protocol.
- Require exactly one canonical Notebook ready marker, zero active top-level patches before promotion, a complete cumulative v3 bundle, SHA match, zero secret hits, and a focused changed test.
- Never evaluate a command string from bundle metadata. Derive direct argument arrays from canonical changed test paths.
- Keep output bounded to booleans, counts, hashes, timings, canonical repo-relative paths, and reason codes.
- Preserve all unrelated dirty-worktree content.

---

## File Map

- Create `scripts/desktop_patchdrop_auto_intake_lib.ps1`: pure marker discovery, patch-target parsing, allowlist/denylist classification, and archive-set calculation.
- Create `scripts/desktop_patchdrop_auto_intake.ps1`: Desktop authority gate, promotion, lease lifecycle, apply, bounded verification, rollback, archive, and result JSON.
- Create `scripts/desktop_patchdrop_auto_intake_tests.ps1`: temp-root RED/GREEN contract tests with stubbed existing guards and real patch fixtures.
- Modify `scripts/goal_next.ps1`: invoke intake before the existing auto runner on non-status requests; preserve argument forwarding and status behavior.
- Use existing `__patch_drop__/janitor_promote_producer_pending.ps1`, `source_edit_session.ps1`, and `janitor_apply_one.ps1` unchanged.
- Publish the already verified Notebook tool-lab files with existing `__patch_drop__/producer_bundle.ps1`; exclude generated `__pycache__` and `.pyc` files.

---

### Task 1: Pure discovery and tooling-scope classifier

**Files:**

- Create: `scripts/desktop_patchdrop_auto_intake_lib.ps1`
- Create: `scripts/desktop_patchdrop_auto_intake_tests.ps1`

**Interfaces:**

- Produces: `Get-AwxNotebookReadyCandidate -PatchDropDir [string] -> PSCustomObject`
- Produces: `Get-AwxGitPatchTargets -PatchText [string] -> string[]`
- Produces: `Get-AwxToolingScopeClassification -Targets [string[]] -> PSCustomObject`
- Produces: `Get-AwxFocusedVerificationPlan -Targets [string[]] -> PSCustomObject`
- Consumers: Task 2 orchestrator and Task 3 wrapper integration tests

- [ ] **Step 1: Write the discovery RED fixtures**

Add a temp-root harness that creates `__patch_drop__/notebook/` and writes marker names without secrets. The first assertions must cover no marker, exactly one canonical marker, two markers, and a marker whose topic slug is invalid:

```powershell
$none = Get-AwxNotebookReadyCandidate -PatchDropDir $patchDrop
Assert-Equal 'no marker reason' $none.reason 'no-ready-bundle'
Assert-Equal 'no marker count' ([int]$none.markerCount) 0

Set-Content -LiteralPath (Join-Path $patchDrop 'stochastic-tool-lab.notebook-pending.md') -Value 'ready' -Encoding UTF8
$one = Get-AwxNotebookReadyCandidate -PatchDropDir $patchDrop
Assert-Equal 'one marker reason' $one.reason 'candidate-ready'
Assert-Equal 'one marker topic' $one.topic 'stochastic-tool-lab'
```

- [ ] **Step 2: Run the focused script and confirm RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Discovery
```

Expected: nonzero exit with a missing `Get-AwxNotebookReadyCandidate` function.

- [ ] **Step 3: Implement deterministic marker discovery**

Implement ordinal, case-sensitive grammar and exact nested bundle checks:

```powershell
function Get-AwxNotebookReadyCandidate {
    param([Parameter(Mandatory = $true)][string]$PatchDropDir)
    $markers = @(Get-ChildItem -LiteralPath $PatchDropDir -File -Filter '*.notebook-pending.md' -ErrorAction SilentlyContinue | Sort-Object Name)
    if ($markers.Count -eq 0) {
        return [pscustomobject]@{ ok = $true; reason = 'no-ready-bundle'; markerCount = 0; topic = ''; markerPath = '' }
    }
    if ($markers.Count -ne 1) {
        return [pscustomobject]@{ ok = $false; reason = 'patch-drop-pending'; markerCount = $markers.Count; topic = ''; markerPath = '' }
    }
    $match = [regex]::Match($markers[0].Name, '^([a-z0-9](?:[a-z0-9._-]{0,94}[a-z0-9])?)\.notebook-pending\.md$', 'CultureInvariant')
    if (-not $match.Success) {
        return [pscustomobject]@{ ok = $false; reason = 'ready-marker-invalid'; markerCount = 1; topic = ''; markerPath = '' }
    }
    $topic = $match.Groups[1].Value
    $nestedBase = "$topic-notebook-v3"
    $nodeDir = Join-Path $PatchDropDir 'notebook'
    $required = @('.patch', '.report.md', '.verify.log', '.sha256.txt', '.manifest.json') | ForEach-Object { Join-Path $nodeDir ($nestedBase + $_) }
    $missing = @($required | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
    return [pscustomobject]@{
        ok = ($missing.Count -eq 0)
        reason = if ($missing.Count -eq 0) { 'candidate-ready' } else { 'missing-bundle-meta' }
        markerCount = 1
        topic = $topic
        markerPath = $markers[0].FullName
        nestedPatchPath = Join-Path $nodeDir ($nestedBase + '.patch')
        missingCount = $missing.Count
    }
}
```

- [ ] **Step 4: Write patch-target and scope RED cases**

Add fixtures for:

- allowed `.agents/skills/demo1-stochastic-tool-lab/SKILL.md` plus `scripts/test_stochastic_tool_lab_cli.py`;
- forbidden `main/java/com/example/lms/LmsApplication.java`;
- protected `scripts/goal_next.ps1`;
- generated `scripts/__pycache__/test_x.pyc`;
- rename/delete/binary/noncanonical headers;
- mixed allowed and forbidden targets;
- allowed patch with no focused changed test.

Use canonical patch blocks such as:

```powershell
$allowedPatch = @'
diff --git a/.agents/skills/demo1-stochastic-tool-lab/SKILL.md b/.agents/skills/demo1-stochastic-tool-lab/SKILL.md
--- /dev/null
+++ b/.agents/skills/demo1-stochastic-tool-lab/SKILL.md
@@ -0,0 +1 @@
+safe
diff --git a/scripts/test_stochastic_tool_lab_cli.py b/scripts/test_stochastic_tool_lab_cli.py
--- /dev/null
+++ b/scripts/test_stochastic_tool_lab_cli.py
@@ -0,0 +1 @@
+def test_safe(): assert True
'@
```

- [ ] **Step 5: Implement patch parsing, scope, and verification-plan functions**

`Get-AwxGitPatchTargets` must accept one `diff --git a/path b/path` header per block, require matching old/new canonical paths except `/dev/null` for additions, reject renames/deletes/binary content, and return unique ordinal paths. `Get-AwxToolingScopeClassification` must return `{ eligible, reason, targetCount, forbiddenCount, generatedCount, protectedCount }`.

Implement constants in the library:

```powershell
$script:AwxAllowedPrefixes = @('.agents/skills/', '.codex/skills/', '.superpowers/', 'agent-prompts/', 'docs/superpowers/', 'tools/', 'verification/', 'data/agent-handoff/')
$script:AwxProtectedExact = @('scripts/goal_next.ps1', 'scripts/goal_next_auto.ps1', 'scripts/desktop_patchdrop_auto_intake.ps1', 'scripts/desktop_patchdrop_auto_intake_lib.ps1', 'scripts/desktop_patchdrop_auto_intake_tests.ps1', 'AGENTS.md')
$script:AwxGeneratedPattern = '(^|/)(__pycache__|\.gradle|build|node_modules|\.next|\.turbo|\.swc)(/|$)|\.pyc$'
```

Treat `scripts/` as eligible only for `scripts/test_*.py` and `scripts/*_tests.ps1`. `Get-AwxFocusedVerificationPlan` returns sorted `pythonTests`, sorted `powerShellTests`, and `focusedTestCount`; zero tests yields `focused-test-missing`.

- [ ] **Step 6: Run focused classifier tests to GREEN**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Discovery
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Scope
```

Expected: exit 0 and `[desktop-auto-intake-test][SUMMARY] failed=0` for each case.

- [ ] **Step 7: Record the task checkpoint**

Run:

```powershell
Get-FileHash .\scripts\desktop_patchdrop_auto_intake_lib.ps1, .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Algorithm SHA256
```

Expected: two hashes; no commit is created.

---

### Task 2: Desktop orchestration, verification, rollback, and archive

**Files:**

- Create: `scripts/desktop_patchdrop_auto_intake.ps1`
- Modify: `scripts/desktop_patchdrop_auto_intake_tests.ps1`
- Reuse unchanged: `__patch_drop__/janitor_promote_producer_pending.ps1`
- Reuse unchanged: `__patch_drop__/source_edit_session.ps1`
- Reuse unchanged: `__patch_drop__/janitor_apply_one.ps1`

**Interfaces:**

- Consumes: all four Task 1 library functions
- Produces CLI: `desktop_patchdrop_auto_intake.ps1 -Root [string] [-Status] [-RequireDesktopCanonicalRoot] [-TimeoutSeconds int]`
- Produces result: `var/codex-smoke/desktop-auto-intake/latest.json` with schema `awx.desktop_auto_intake.result.v1`
- Produces exit codes: 0 for applied/no-ready/safe non-Desktop no-op, 2 for HOLD, 4 for secret risk, 5 for rollback failure

- [ ] **Step 1: Add orchestration RED fixtures with stub guards**

Create fake-root copies of minimal guard scripts that append ordered event names to `events.log`. The fake janitor must apply the supplied patch with `git apply`; the fake source session must record `begin` and `end`. Assert the expected success order:

```text
promote
lease-begin
apply
pytest
reverse-check
lease-end
```

Add failure fixtures for promotion, lease, apply, pytest, reverse-check, secret scan, archive collision, and rollback apply. Each fixture asserts final target bytes and whether the marker/top-level bundle remains, moves to `applied`, or moves to `rejected`.

- [ ] **Step 2: Run orchestration RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Lifecycle
```

Expected: nonzero because `desktop_patchdrop_auto_intake.ps1` does not exist.

- [ ] **Step 3: Implement authority and pre-mutation gates**

The script resolves `-Root`, imports the library, and computes a result object once. With `-RequireDesktopCanonicalRoot`, require all of:

```powershell
$expectedDesktopRoot = [IO.Path]::GetFullPath('C:\AbandonWare\demo-1\demo-1\src').TrimEnd('\')
$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path.TrimEnd('\')
$driveName = ([IO.Path]::GetPathRoot($resolvedRoot)).TrimEnd('\').TrimEnd(':')
$drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
$isMapped = $null -ne $drive -and -not [string]::IsNullOrWhiteSpace([string]$drive.DisplayRoot)
$isDesktop = $resolvedRoot.Equals($expectedDesktopRoot, [StringComparison]::OrdinalIgnoreCase) -and -not $isMapped -and -not $resolvedRoot.StartsWith('\\')
```

Do not output `DisplayRoot`. `not-desktop-canonical-root` is a safe exit-0 no-op. Check `.git/index.lock`, active top-level patches, candidate count, complete sidecars, patch classification, focused-test count, and secret count before promotion.

- [ ] **Step 4: Implement bounded direct process execution**

Add `Invoke-AwxBoundedProcess` using `System.Diagnostics.ProcessStartInfo.ArgumentList` when available and a safely quoted argument fallback for Windows PowerShell 5.1. Redirect stdout/stderr to temp files, wait no more than `TimeoutSeconds`, kill on timeout, return `{ exitCode, timedOut, outputHash, outputLineCount, reason }`, and delete temp files in `finally`. Persist only bounded redacted tail lines in the result artifact.

Never concatenate a producer-supplied command. The only dynamic arguments are canonical repo-relative test paths returned by Task 1.

- [ ] **Step 5: Implement promotion, lease, and apply lifecycle**

Use direct PowerShell argument arrays:

```powershell
$ownerId = 'desktop-auto-intake-' + [guid]::NewGuid().ToString('N')
$promoteArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $promoteScript, '-Topic', $candidate.topic, '-Node', 'notebook')
$leaseBeginArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $leaseScript, '-Action', 'begin', '-Role', 'desktop-consumer', '-Root', $resolvedRoot, '-Topic', $candidate.topic, '-OwnerId', $ownerId, '-TtlMinutes', '30')
$applyArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $applyScript, '-PatchName', ($candidate.topic + '-v3.patch'), '-SourceLeaseOwnerId', $ownerId)
```

Set `leaseHeld=true` only after a zero exit from begin. Always call matching `end` in `finally` when held.

- [ ] **Step 6: Implement derived Desktop verification**

After apply, run in this order:

```text
git diff --check
python -m pytest -q changed-python-test-1 changed-python-test-2
powershell -NoProfile -ExecutionPolicy Bypass -File changed_test_script.ps1
git apply --reverse --check exact-verified-patch-snapshot
```

Skip a language lane only when its changed-test list is empty. Hash the exact top-level patch into a private temp snapshot before apply and use that same snapshot for reverse-check and rollback. Count high-confidence secret patterns over `git diff --` output without printing matches.

- [ ] **Step 7: Implement rollback and transactional archive**

On any post-apply failure, while the lease is held:

```powershell
git apply --reverse --check $patchSnapshot
git apply --reverse $patchSnapshot
git apply --check $patchSnapshot
```

If all three succeed, move the five top-level bundle files and ready marker to `__patch_drop__/rejected/` and write `topic-v3.reason.txt` containing only the reason code. Move sidecars first and the patch last, track every move, and move records back on an archive failure.

On success, transactionally move the five top-level files and ready marker to `applied/`. Leave the nested producer bundle as immutable producer evidence. Record bundle SHA, target/test counts, secret count, durations, lease lifecycle, rollback status, and `desktopFinalProof=PASS` in the result JSON.

- [ ] **Step 8: Run lifecycle tests to GREEN**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Lifecycle
```

Expected: exit 0; all success, HOLD, rollback, and archive fixtures pass; raw secret and absolute-backing-path hit counts remain zero.

- [ ] **Step 9: Record the task checkpoint**

Run:

```powershell
Get-FileHash .\scripts\desktop_patchdrop_auto_intake.ps1, .\scripts\desktop_patchdrop_auto_intake_lib.ps1, .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Algorithm SHA256
```

Expected: three hashes; no commit is created.

---

### Task 3: Connect the normal goal-next entrypoint

**Files:**

- Modify: `scripts/goal_next.ps1`
- Modify: `scripts/desktop_patchdrop_auto_intake_tests.ps1`

**Interfaces:**

- Consumes: Task 2 CLI
- Preserves: every existing `goal_next.ps1` argument and exit code
- Produces: non-status call order `desktop_patchdrop_auto_intake.ps1` then `goal_next_auto.ps1 -EnsureFresh`

- [ ] **Step 1: Add wrapper-order RED tests**

Copy `goal_next.ps1` into a fake root with stub intake and auto scripts. The stubs append `intake` and `auto` to `events.log`. Prove:

1. default and explicit non-status calls write `intake,auto`;
2. a fresh latest state cannot bypass `intake` because intake is invoked by the wrapper first;
3. `-Status` writes only `auto`;
4. intake exit 2 stops before `auto` and the wrapper returns 2;
5. all existing topic/external/Supabase/web arguments still reach `goal_next_auto.ps1` unchanged.

- [ ] **Step 2: Run wrapper RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Wrapper
```

Expected: nonzero because current `goal_next.ps1` does not invoke intake.

- [ ] **Step 3: Add the minimal wrapper hook**

Immediately after `$autoScript` resolution, resolve `$intakeScript`. Before building or invoking the existing auto runner, add:

```powershell
if (-not $Status) {
    $intakeArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $intakeScript, '-RequireDesktopCanonicalRoot')
    if (-not [string]::IsNullOrWhiteSpace($Root)) { $intakeArgs += @('-Root', $Root) }
    & powershell @intakeArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

Do not change the `-Status` delegation or any existing option forwarding.

- [ ] **Step 4: Run wrapper and existing goal-next tests**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Case Wrapper
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\goal_next_auto_tests.ps1
```

Expected: both exit 0 with failure count zero.

- [ ] **Step 5: Prove Notebook execution is a no-op**

Run from `Y:\`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake.ps1 -Root Y:\ -RequireDesktopCanonicalRoot -Status
```

Expected: exit 0, `reason=not-desktop-canonical-root`, no lease, no promotion, no top-level patch, and no backing path output.

- [ ] **Step 6: Record the task checkpoint**

Run:

```powershell
Get-FileHash .\scripts\goal_next.ps1, .\scripts\desktop_patchdrop_auto_intake.ps1, .\scripts\desktop_patchdrop_auto_intake_tests.ps1 -Algorithm SHA256
```

Expected: three hashes; no commit is created.

---

### Task 4: Full regression and Notebook tool-lab ready bundle

**Files:**

- Verify unchanged: `__patch_drop__/janitor_promote_producer_pending.ps1`
- Verify unchanged: `__patch_drop__/janitor_apply_one.ps1`
- Publish: `__patch_drop__/notebook/stochastic-tool-lab-notebook-v3.*`
- Publish last: `__patch_drop__/stochastic-tool-lab.notebook-pending.md`

**Interfaces:**

- Consumes: Tasks 1-3 and the existing local producer worktree
- Produces: one complete, SHA-covered Notebook v3 bundle with `desktopFinalProof=evidence_needed`
- Desktop continuation: the next normal non-status `goal_next.ps1` run consumes it automatically from the Desktop canonical root

- [ ] **Step 1: Run the full local regression ladder**

Run sequentially:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_promote_producer_pending_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\goal_next_auto_tests.ps1
```

Expected: every command exits 0 and reports zero failures.

- [ ] **Step 2: Run count-only changed-file safety checks**

Run a script that reads only the four implementation files and the design/plan, then reports:

```text
secretPatternHits=0
rawBackingPathHits=0
generatedArtifactPathHits=0
```

Do not print matching text.

- [ ] **Step 3: Enumerate the verified tool-lab pathspec**

From `C:\AbandonWare\worktrees\awx-notebook-stochastic-tool-lab`, build explicit pathspec entries from these task-owned roots only:

```text
.agents/skills/demo1-stochastic-tool-lab
data/agent-handoff/stochastic-tool-lab
scripts/test_demo1_stochastic_tool_lab_skill.py
scripts/test_stochastic_tool_lab_adapter.py
scripts/test_stochastic_tool_lab_cli.py
scripts/test_stochastic_tool_lab_contracts.py
scripts/test_stochastic_tool_lab_scoring.py
scripts/test_stochastic_tool_lab_selftest.py
scripts/test_stochastic_tool_lab_tri_query.py
tools/stochastic-tool-lab
tools/stochastic_tool_lab
verification/stochastic-tool-lab
```

If a listed root is absent, omit it with `pathspec-absent`; do not substitute another directory. Reject any enumerated `__pycache__` or `.pyc` entry.

- [ ] **Step 4: Re-run the tool-lab focused proof before publication**

Run the existing verified test command from that local worktree and require the observed focused total to remain 120/120. Run the upstream and wrapper suites already named in its final report and require their recorded totals to remain 68/68, lower-level 17, and runner 11. Any regression keeps publication on HOLD.

- [ ] **Step 5: Create the cumulative v3 producer bundle**

Invoke the repository-owned producer from `Y:\` with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File Y:\__patch_drop__\producer_bundle.ps1 `
  -Topic stochastic-tool-lab `
  -Node notebook `
  -SourceRoot C:\AbandonWare\worktrees\awx-notebook-stochastic-tool-lab `
  -PatchDropRoot Y:\__patch_drop__ `
  -PathSpec $verifiedPathSpec
```

Expected: one nested v3 patch plus report, verify log, manifest, SHA sidecar, and a ready marker written last; `secretPatternHits=0`; `sourceIsolation.guard=PASS`; `desktopFinalProof=evidence_needed`.

- [ ] **Step 6: Verify the published bundle without promoting it**

Run `janitor_inventory.ps1`, recompute every SHA entry, verify the manifest pins `stochastic-tool-lab-notebook-v3.patch`, count top-level patches, and scan secret patterns count-only.

Expected:

```text
topLevelPatchCount=0
nestedBundleComplete=true
shaMismatchCount=0
secretPatternHits=0
desktopFinalProof=evidence_needed
```

- [ ] **Step 7: Record final supporting evidence**

Write a bounded result under `data/agent-handoff/notebook-desktop-auto-intake/` containing only canonical paths, file counts, SHA-256 values, test totals, reason codes, `runtimeLineageVerdict=HOLD`, and `desktopFinalProof=evidence_needed`. Do not claim the real Desktop applied the bundle until its normal command produces canonical Desktop proof.

---

## Plan Self-Review

- Spec coverage: discovery, scope, Desktop authority, status behavior, promotion, lease, apply, derived verification, rollback, archive, failure classes, and real bundle publication each have a task.
- Placeholder scan: all commands, paths, interfaces, reason codes, and assertions are explicit.
- Type consistency: candidate fields and helper names are identical between Tasks 1-3; lifecycle exit codes and result schema are defined once.
- Scope check: application-source automation, background persistence, commits, external services, and unrelated refactoring remain excluded.
