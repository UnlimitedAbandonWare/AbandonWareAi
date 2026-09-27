# Demo1 Desktop Y-to-C Directive Rebinding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing repo-local Desktop intake skill so Notebook `Y:\` directive paths are safely rebound to the canonical Desktop checkout without granting Y-side mutation authority.

**Architecture:** Keep `demo1-desktop-canonical-goal-intake` as a thin router. Put detailed stage and rebinding rules in one reference, and enforce lexical/live path constraints with a read-only PowerShell resolver whose output never authorizes mutation. Reuse the existing three-way preflight, source lease, Desktop proof, and external-evidence skills.

**Tech Stack:** Markdown Agent Skills, PowerShell 5.1-compatible script, repo skill-family validator, Codex skill `quick_validate.py`, fresh-context subagent pressure tests.

## Global Constraints

- Canonical Desktop execution/read/write/final-proof root is exactly `C:\AbandonWare\demo-1\demo-1\src`.
- `Y:\` evidence authority is `supporting_only`; `fallbackWriteRoot=null`.
- Do not modify application source, `AGENTS.md`, `agent-prompts`, Gradle files, DB, public API, credentials, or environment-variable names.
- Supabase is optional read-only supporting evidence; no Supabase or DB mutation.
- Do not create a second preflight, lease, CAS, PatchDrop, rollback, or verification protocol.
- Do not commit, push, deploy, or send external messages.
- All edits use `apply_patch`; metadata regeneration may use the official deterministic generator.
- Desktop live proof remains `desktopFinalProof=evidence_needed` on Notebook.

---

### Task 1: Establish RED behavior and resolver tests

**Files:**
- Create: `.agents/skills/demo1-desktop-canonical-goal-intake/tests/resolve_desktop_directive_target.tests.ps1`
- Read: `.agents/skills/demo1-desktop-canonical-goal-intake/SKILL.md`

**Interfaces:**
- Consumes: the current unmodified skill and five fresh-context routing prompts.
- Produces: baseline observations plus an executable test contract for `scripts/resolve_desktop_directive_target.ps1`.

- [ ] **Step 1: Run five no-new-guidance control samples**

Use fresh subagents and the current skill. Cover these pressures across the five samples: urgent direct patching, a claimed Y/C byte match, a `Y:\..\outside` path, a mixed `D:\` target, Y-side preimage claims, Stage 2 without a fresh RED, and a request to keep the Stage 1 lease.

Required manual assertions for every response:

```text
Y source write authorized = false
canonicalExecutionRoot = C:\AbandonWare\demo-1\demo-1\src
Y evidence authority = supporting_only
path traversal or foreign drive = HOLD
Y hash alone proves C preimage = false
Stage 2 reuses Stage 1 lease = false
Stage 2 production candidate count <= 1
commit/push/deploy = false
```

- [ ] **Step 2: Record the actual control gap**

Classify each observed miss as one of:

```text
missing-structural-field
unsafe-path-rebinding
authority-escalation
stage-boundary-collapse
no-observed-behavior-gap
```

If all five agents comply, do not add discipline prose merely to restate existing rules. Continue only with the deterministic resolver gap and compact structural slots.

- [ ] **Step 3: Write the resolver test before the resolver exists**

Create a PowerShell test runner that invokes the missing resolver and checks these cases:

```powershell
$cases = @(
    @{ Name='y-path'; Input='Y:\main\java\Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='relative-path'; Input='main/java/Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='canonical-c-path'; Input='C:\AbandonWare\demo-1\demo-1\src\main\java\Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='parent-segment'; Input='Y:\..\outside.txt'; Reason='directive-path-escape'; Rel=$null },
    @{ Name='unc'; Input='\\server\share\file.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='foreign-drive'; Input='D:\file.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='ads'; Input='Y:\main\file.txt:stream'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='root-only'; Input='Y:\'; Reason='directive-target-empty'; Rel=$null }
)
```

Use this runner body after `$cases`:

```powershell
$ErrorActionPreference = 'Stop'
$resolver = Join-Path $PSScriptRoot '..\scripts\resolve_desktop_directive_target.ps1'
if (-not (Test-Path -LiteralPath $resolver -PathType Leaf)) {
    Write-Error 'resolver-script-missing'
    exit 1
}

$failures = [System.Collections.Generic.List[string]]::new()
foreach ($case in $cases) {
    $json = & powershell -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -DirectivePath $case.Input -LexicalOnly
    if ($LASTEXITCODE -ne 0) {
        $failures.Add("$($case.Name):resolver-exit")
        continue
    }
    try { $result = $json | ConvertFrom-Json -ErrorAction Stop }
    catch {
        $failures.Add("$($case.Name):json-invalid")
        continue
    }
    if ($result.reason -ne $case.Reason) {
        $failures.Add("$($case.Name):reason")
    }
    if ($result.authorizedMutation -ne $false) {
        $failures.Add("$($case.Name):mutation-authorized")
    }
    $actualRel = if (@($result.targets).Count -eq 1) {
        [string]$result.targets[0].targetRel
    } else { $null }
    if ($actualRel -ne $case.Rel) {
        $failures.Add("$($case.Name):target-rel")
    }
}

if ($failures.Count -ne 0) {
    Write-Error ('resolver-tests-failed count=' + $failures.Count +
        ' cases=' + (($failures | Sort-Object) -join ','))
    exit 1
}
'PASS cases=8'
```

The runner reports bounded case names rather than rejected raw input values.

- [ ] **Step 4: Run the test and verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-desktop-canonical-goal-intake\tests\resolve_desktop_directive_target.tests.ps1
```

Expected: nonzero exit with `resolver-script-missing`. A syntax error or unrelated access error is not an acceptable RED.

- [ ] **Step 5: Record no-commit evidence**

Record the RED reason and baseline result counts in the session report. Do not commit.

---

### Task 2: Implement the read-only target resolver

**Files:**
- Create: `.agents/skills/demo1-desktop-canonical-goal-intake/scripts/resolve_desktop_directive_target.ps1`
- Test: `.agents/skills/demo1-desktop-canonical-goal-intake/tests/resolve_desktop_directive_target.tests.ps1`

**Interfaces:**
- Consumes: `-DirectivePath <string[]>` and optional `-LexicalOnly`.
- Produces: one compressed JSON object containing `status`, `reason`, fixed root fields, `authorizedMutation=false`, and bounded target rows.

- [ ] **Step 1: Implement the fixed parameters and output shape**

Use this public interface:

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$DirectivePath,
    [switch]$LexicalOnly
)
```

Return this shape without raw UNC or rejected path values:

```text
schemaVersion=demo1.desktop-directive-target.v1
status=PASS|HOLD
reason=match|lexical-only|<failure-code>
canonicalExecutionRoot=C:\AbandonWare\demo-1\demo-1\src
yEvidenceAuthority=supporting_only
fallbackWriteRoot=null
authorizedMutation=false
routingVerified=true|false
targets=[index,targetRel,desktopTarget,withinRoot,exists,reparseRisk]
desktopFinalProof=evidence_needed
evidenceNeeded=<null-or-one-proof>
```

Start the implementation with these fixed helpers:

```powershell
$ErrorActionPreference = 'Stop'
$fixedRootLiteral = 'C:\AbandonWare\demo-1\demo-1\src'
$fixedRoot = [IO.Path]::GetFullPath($fixedRootLiteral).TrimEnd('\')
$targetRows = [System.Collections.Generic.List[object]]::new()

function Stop-Rebinding([string]$Reason) {
    throw [InvalidOperationException]::new($Reason)
}

function Write-Result(
    [string]$Status,
    [string]$Reason,
    [bool]$RoutingVerified,
    [object[]]$Targets,
    [string]$EvidenceNeeded
) {
    [pscustomobject][ordered]@{
        schemaVersion = 'demo1.desktop-directive-target.v1'
        status = $Status
        reason = $Reason
        canonicalExecutionRoot = $fixedRootLiteral
        yEvidenceAuthority = 'supporting_only'
        fallbackWriteRoot = $null
        authorizedMutation = $false
        routingVerified = $RoutingVerified
        targets = @($Targets)
        desktopFinalProof = 'evidence_needed'
        evidenceNeeded = $EvidenceNeeded
    } | ConvertTo-Json -Depth 5 -Compress
}
```

- [ ] **Step 2: Implement lexical normalization**

Accept only repo-relative input, exact `Y:\` descendants, or exact canonical C-root descendants. Normalize `/` to `\`. Reject other rooted paths, UNC, ADS, empty targets, and explicit `..` segments before calling `GetFullPath`. Ensure every C candidate starts with the fixed root plus a separator and is not the root itself.

Implement each input using this logic:

```powershell
$normalizedInput = $raw.Trim().Replace('/', '\')
if ([string]::IsNullOrWhiteSpace($normalizedInput)) {
    Stop-Rebinding 'directive-target-empty'
}
if ($normalizedInput.StartsWith('\\')) {
    Stop-Rebinding 'directive-path-unsupported'
}

if ($normalizedInput -match '^[Yy]:\\') {
    $relative = $normalizedInput.Substring(3)
} elseif ([IO.Path]::IsPathRooted($normalizedInput)) {
    $absoluteInput = [IO.Path]::GetFullPath($normalizedInput).TrimEnd('\')
    if (-not $absoluteInput.StartsWith(
        $fixedRoot + '\', [StringComparison]::OrdinalIgnoreCase
    )) { Stop-Rebinding 'directive-path-unsupported' }
    $relative = $absoluteInput.Substring($fixedRoot.Length + 1)
} else {
    $relative = $normalizedInput
}

$segments = @($relative.Split('\') | Where-Object { $_ -ne '' })
if ($segments.Count -eq 0) { Stop-Rebinding 'directive-target-empty' }
if ($segments -contains '..') { Stop-Rebinding 'directive-path-escape' }
if (@($segments | Where-Object { $_.Contains(':') }).Count -ne 0) {
    Stop-Rebinding 'directive-path-unsupported'
}

$targetRel = $segments -join '\'
$desktopTarget = [IO.Path]::GetFullPath((Join-Path $fixedRoot $targetRel))
if (-not $desktopTarget.StartsWith(
    $fixedRoot + '\', [StringComparison]::OrdinalIgnoreCase
)) { Stop-Rebinding 'directive-path-escape' }
```

- [ ] **Step 3: Implement live Desktop checks**

Unless `-LexicalOnly` is set, require:

```text
fixed C root exists
git rev-parse --show-toplevel returns exactly one result
resolved Git top-level equals the fixed C root and is non-UNC
each target exists
no target or ancestor below the root is a reparse point
```

Capture Git output internally. On failure emit only a reason code and the fixed public root. Do not modify Git trust settings.

Use this bounded check before target traversal:

```powershell
if (-not $LexicalOnly) {
    if (-not (Test-Path -LiteralPath $fixedRoot -PathType Container)) {
        Stop-Rebinding 'c-canonical-unavailable'
    }
    $gitTopRows = @(& git -C $fixedRoot rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or $gitTopRows.Count -ne 1) {
        Stop-Rebinding 'c-canonical-unavailable'
    }
    $gitTop = [IO.Path]::GetFullPath([string]$gitTopRows[0]).TrimEnd('\')
    if ($gitTop.StartsWith('\\') -or -not $gitTop.Equals(
        $fixedRoot, [StringComparison]::OrdinalIgnoreCase
    )) { Stop-Rebinding 'c-canonical-unavailable' }
}
```

For each target in live mode, require `Test-Path -PathType Leaf`, then walk from `Get-Item` to the fixed root and reject any `[IO.FileAttributes]::ReparsePoint`. Add only the fixed C target, relative target, booleans, and input index to `$targetRows`.

- [ ] **Step 4: Keep lexical mode non-authorizing**

`-LexicalOnly` returns `status=HOLD`, `reason=lexical-only`, `routingVerified=false`, and `authorizedMutation=false` even for valid paths.

Wrap processing in one fail-closed result boundary:

```powershell
try {
    # Normalize and validate every DirectivePath item, appending target rows.
    if ($LexicalOnly) {
        Write-Result 'HOLD' 'lexical-only' $false $targetRows.ToArray() `
            'Run live validation from the Desktop canonical root.'
    } else {
        Write-Result 'PASS' 'match' $true $targetRows.ToArray() $null
    }
} catch {
    $reason = if ($_.Exception.Message -match '^[a-z0-9-]+$') {
        $_.Exception.Message
    } else { 'directive-path-unsupported' }
    Write-Result 'HOLD' $reason $false @() 'Correct the directive target and rerun.'
}
```

- [ ] **Step 5: Run GREEN tests**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-desktop-canonical-goal-intake\tests\resolve_desktop_directive_target.tests.ps1
```

Expected: `PASS cases=8`, exit code 0, raw rejected paths absent from output.

- [ ] **Step 6: Record no-commit evidence**

Record the test count and resolver SHA-256. Do not commit.

---

### Task 3: Refactor the intake skill and add the rebinding contract

**Files:**
- Modify: `.agents/skills/demo1-desktop-canonical-goal-intake/SKILL.md`
- Create: `.agents/skills/demo1-desktop-canonical-goal-intake/references/directive-rebinding-contract.md`
- Modify: `.agents/skills/demo1-desktop-canonical-goal-intake/agents/openai.yaml`

**Interfaces:**
- Consumes: resolver output plus existing Desktop intake evidence.
- Produces: the existing `demo1.c-canonical-goal-intake.v1` intake object with an added `directiveRebinding` slot and an optional two-stage execution contract.

- [ ] **Step 1: Tighten discovery metadata**

Use this trigger-only frontmatter:

```yaml
---
name: demo1-desktop-canonical-goal-intake
description: Use when a demo-1 Desktop task receives a Notebook or SMB-originated directive containing Y:\ paths, mixed Y/C roots, or uncertain source ownership.
---
```

- [ ] **Step 2: Keep SKILL.md thin**

Preserve the fixed root invariant and existing lane selection. Move the detailed output schema to the reference and add these required rules:

```text
run resolver before accepting any Y/C/relative target
resolver authorizedMutation is always false
promote only C-side live revision/sourceSet/preimage proof
delegate application source to demo1-source-edit-three-way-preflight
release Stage 1 lease before Stage 2 discovery
fresh preflight and lease for Stage 2
additional production candidate maximum = 1
```

Keep SKILL.md at or below 160 lines and 1200 words.

- [ ] **Step 3: Write the detailed reference**

Include input classification, resolver command, `directiveRebinding` output fields, two-stage rules, failure labels, quick reference, common mistakes, and one `AnswerQualityEvaluator`/`EvidenceRepairHandler` example. Treat those class names as example targets only.

- [ ] **Step 4: Regenerate UI metadata**

Run:

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\generate_openai_yaml.py `
  .\.agents\skills\demo1-desktop-canonical-goal-intake `
  --interface 'display_name=Demo1 Desktop Y-to-C Intake' `
  --interface 'short_description=Safely rebind Notebook Y paths to Desktop C' `
  --interface 'default_prompt=Use $demo1-desktop-canonical-goal-intake to rebind a Notebook Y-drive directive to the Desktop canonical checkout.'
```

Expected: quoted interface strings and a default prompt containing the exact skill token.

- [ ] **Step 5: Run structural validation**

Run:

```powershell
$env:PYTHONUTF8='1'
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-desktop-canonical-goal-intake
```

Expected: `Skill is valid!`

- [ ] **Step 6: Record no-commit evidence**

Record changed artifact paths and their hashes. Do not commit.

---

### Task 4: Forward-test and postprocess the completed skill

**Files:**
- Verify: `.agents/skills/demo1-desktop-canonical-goal-intake/**`
- Do not create persistent report files unless a validator failure needs detail.

**Interfaces:**
- Consumes: final skill, reference, resolver, and test runner.
- Produces: behavior-test counts, structural validation counts, secret-scan count, and `desktopFinalProof=evidence_needed`.

- [ ] **Step 1: Run five fresh-context skill samples**

Reuse the exact five control prompts from Task 1, now explicitly requiring the edited skill. Manually check every output against the eight assertions from Task 1. Expected: all five converge on C-rooted HOLD/APPLY gating with no Y write authorization.

- [ ] **Step 2: Classify new rationalizations**

Look for these bypasses:

```text
same bytes means same authority
SMB speed means Y is safe to write
lexical-only PASS authorizes mutation
GREEN permits cleanup without RED
one continuous lease is safer or faster
Supabase evidence permits DB repair
```

Add a rationalization table or red-flags section only for bypasses actually observed. Re-run affected samples after any change.

- [ ] **Step 3: Run the resolver tests and individual skill validation again**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-desktop-canonical-goal-intake\tests\resolve_desktop_directive_target.tests.ps1
$env:PYTHONUTF8='1'
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-desktop-canonical-goal-intake
```

Expected: resolver `PASS cases=8`; skill valid.

- [ ] **Step 4: Run the repo skill-family postprocessor**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 `
  -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 `
  -TrimCandidateCount 5 -SummaryJson
```

Expected: exit 0, `ok=true`, no trigger, metadata, discovery, coverage, budget, scaffold, or secret issue attributable to the changed skill.

- [ ] **Step 5: Run a count-only targeted secret scan**

Scan only the four changed skill artifacts and test runner. Report count only. Expected: 0.

- [ ] **Step 6: Verify scope and hand off**

Confirm application-source change count is 0, no `AGENTS.md`/`agent-prompts`/Gradle file was changed, no commit/push/deploy occurred, and Desktop live root proof remains `evidence_needed`.

---

## Plan Self-Review Result

- Spec coverage: path classification, Y evidence demotion, C-root binding, two-stage lease boundary, external-lane limits, rollback ownership, validation, and no-commit constraints each map to a task.
- Placeholder scan: no unfinished markers, generic error-handling steps, or undefined implementation steps remain.
- Interface consistency: resolver parameter names, JSON fields, failure codes, and test expectations are identical across Tasks 1–4.
