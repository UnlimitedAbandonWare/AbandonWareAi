# Demo-1 Source-Edit Three-Way Preflight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `demo-1`-local Codex preflight that detects explicit application-source modification intent, produces exactly three bounded review packets over one frozen EvidenceSnapshot, and hands only a stable `APPLY` verdict to the existing source-owner guard.

**Architecture:** A project-local `UserPromptSubmit` command hook performs a high-precision, no-storage classification and injects only a short skill-routing instruction. A compact repo-local skill owns `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` as logical roles; phase 1 spawns no mandatory subagents and does not load the full chat-specific prompt pack. Existing Desktop, Y-drive guarded-direct, and PatchDrop workflows retain exclusive ownership of actual source mutation.

**Tech Stack:** Windows PowerShell 5.1+, JSON Codex hook configuration, Markdown repo-local skills and policy, existing Python three-way grader/tests, repository PowerShell contract-test conventions.

## Global Constraints

- Execute implementation only from the Desktop canonical checkout `C:\AbandonWare\demo-1\demo-1\src` after trusted Git preflight succeeds.
- Keep the current Notebook `Y:\` evidence and this plan read-only supporting evidence; do not implement from the Notebook SMB checkout.
- Request class is `prompt_skill_tooling_only`; no `main/**`, `app/**`, `src/test/**`, Gradle, runtime-resource, public-API, DB/DDL, provider, credential, environment, PatchDrop-queue, or user-global Codex file mutation.
- `canonicalQueryCount=3`; the only roles are `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`.
- Use one frozen EvidenceSnapshot, no fourth reviewer, no majority vote, and no evidence acquisition after Positive begins.
- Default execution mode is `single-agent-logical-roles`; phase 1 spawns zero mandatory subagents and hard-codes no model slug or reasoning effort.
- `classifierTimeoutSeconds=2`, `classifierMaxInputBytes=65536`, `classifierMaxOutputBytes=1024`.
- `evidenceRowCountMax=20`, `evidenceSummaryCharsMax=6000`, `positivePacketCharsMax=2400`, `negativePacketCharsMax=2400`, `neutralPacketCharsMax=1800`.
- Never persist or echo the raw prompt, raw response, source snippet, full log, credential, provider error, or full environment dump.
- Large artifacts are represented by path, SHA-256, count, boolean, timing, and redacted reason code only.
- `UserPromptSubmit` has no effective matcher; the script itself performs classification. Hook failure never authorizes source mutation.
- Phase 1 does not add a `PreToolUse` hard gate and does not install a user-global plugin or hook.
- Actual source mutation continues through the existing source-owner guard only after `NEUTRAL_QUERY.verdict=APPLY` and every ownership/safety gate passes.
- Run no Gradle command unless an undeclared application-source/build change is detected; such a change is a stop condition, not permission to widen the plan.
- Commits are forbidden unless the user separately and explicitly authorizes them. Each commit step below is a prepared boundary, not standing authorization.
- Time budget is a 180-minute hard cap. Stop immediately once the implementation and focused evidence gates pass.

---

## File Structure

### Create

- `.codex/hooks/source_edit_triage.ps1` — reads one hook event, classifies explicit source-edit intent, emits bounded additional context, and exposes pure functions for contract tests.
- `.codex/hooks.json` — registers the single project-local `UserPromptSubmit` command handler.
- `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md` — compact trigger, workflow, safety, and routing instructions.
- `.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml` — skill display metadata and explicit invocation prompt.
- `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md` — exact packet schemas, bounds, score, and failure classes.
- `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1` — deterministic RED/GREEN tests for classifier, skill, hook config, and AGENTS routing.

### Modify

- `AGENTS.md` — add one durable routing rule under `Reusable Prompt Packs`; do not copy the full contract.

### Reuse without modification

- `.agents/skills/demo1-agentic-chat-postprocess/references/review-packets.md` — canonical query names and compatible packet-field semantics.
- `scripts/test_three_perspective_chat_postprocess.py` — existing three-way regression suite.
- `scripts/score_three_way_long_tail_design.py` — sealed design meta-grader.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/v2_design_contract.json` — existing sealed design input.
- `.agents/skills/demo1-skill-family-postprocessor/scripts/validate_demo1_skill_family.ps1` — compact skill-family validator.

---

### Task 1: Prove the Desktop execution boundary

**Files:**
- Read: `AGENTS.md`
- Read: `build.gradle.kts:334`
- Read: `app/build.gradle.kts:44`
- Read: `.git/HEAD`
- No mutation in this task.

**Interfaces:**
- Consumes: Desktop root identity, trusted Git metadata, dirty ownership, index lock, PatchDrop queue, source-edit leases, active sourceSets, current hook state.
- Produces: one in-memory preflight decision with `verdict`, `failureClass`, `root`, `branch`, `dirtyTargets`, `indexLock`, `topLevelPatchCount`, `activeLeaseCount`, and `hookConfigured`.

- [ ] **Step 1: Resolve the canonical Desktop root without changing Git trust**

Run in Windows PowerShell on Desktop:

```powershell
$expectedRoot = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location -LiteralPath $expectedRoot
$resolvedRoot = (Resolve-Path -LiteralPath '.').ProviderPath
if (-not [string]::Equals($resolvedRoot, $expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'desktop-root-identity-mismatch'
}
git rev-parse --show-toplevel
git branch --show-current
git worktree list --porcelain
git status --short
```

Expected: Git top-level resolves to the same Desktop path, the branch is non-empty, and Git emits no `dubious ownership` error. Do not run `git config --global --add safe.directory` if it fails; stop with `source-owner-unproven`.

- [ ] **Step 2: Check collision and ownership gates**

Run:

```powershell
$indexLock = Test-Path -LiteralPath '.git\index.lock'
$topLevelPatchCount = @(Get-ChildItem -LiteralPath '__patch_drop__' -Filter '*-v3.patch' -File -ErrorAction SilentlyContinue).Count
$activeLeaseCount = @(Get-ChildItem -LiteralPath '__patch_drop__\source-edit-locks' -File -ErrorAction SilentlyContinue).Count
$declaredTargets = @(
    '.codex/hooks/source_edit_triage.ps1',
    '.codex/hooks.json',
    '.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md',
    '.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml',
    '.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md',
    'scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1',
    'AGENTS.md'
)
$dirty = @(git status --short)
$dirtyOverlap = @($dirty | Where-Object {
    $row = [string]$_
    $declaredTargets | Where-Object { $row.Replace('\','/').EndsWith($_, [StringComparison]::OrdinalIgnoreCase) }
})
[pscustomobject]@{
    indexLock = $indexLock
    topLevelPatchCount = $topLevelPatchCount
    activeLeaseCount = $activeLeaseCount
    dirtyOverlapCount = $dirtyOverlap.Count
    hookConfigured = Test-Path -LiteralPath '.codex\hooks.json'
} | ConvertTo-Json -Compress
```

Expected: `indexLock=false`, `dirtyOverlapCount=0`, and no unknown owner on a declared target. A pending unrelated PatchDrop bundle is recorded but does not authorize touching application source. Any declared-target overlap stops with `dirty-overlap`.

- [ ] **Step 3: Reconfirm the tooling-only boundary**

Run:

```powershell
Select-String -Path 'build.gradle.kts','app\build.gradle.kts' -Pattern 'sourceSets|srcDirs|java_clean|main/resources|main/java'
Test-Path -LiteralPath '.agents\skills\demo1-agentic-chat-postprocess\references\review-packets.md'
Test-Path -LiteralPath 'scripts\test_three_perspective_chat_postprocess.py'
Test-Path -LiteralPath 'scripts\score_three_way_long_tail_design.py'
```

Expected: root `main/java` + `main/resources` and app `src/main/java_clean` + `src/main/resources` remain the active application surfaces, and every reusable three-way asset exists. Record them as excluded/reused respectively.

- [ ] **Step 4: Record the execution decision**

The implementer must state exactly one result before Task 2:

```text
verdict=APPLY
failureClass=none
mutationSurface=repo-local-skill-hook-test-and-one-AGENTS-line
applicationSourceMutation=false
desktopFinalProof=evidence_needed
```

If any Task 1 gate fails, replace `APPLY` with `HOLD`, name the single failure class, and stop.

---

### Task 2: Build the bounded source-edit intent classifier with TDD

**Files:**
- Create: `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`
- Create: `.codex/hooks/source_edit_triage.ps1`

**Interfaces:**
- Consumes: `UserPromptSubmit` JSON on stdin with optional string field `prompt`.
- Produces: empty stdout for a non-trigger or malformed/oversized input; otherwise one compact JSON object containing `hookSpecificOutput.hookEventName=UserPromptSubmit` and bounded `additionalContext`.
- Produces pure test interfaces: `Test-SourceEditIntent([string]) -> [bool]`, `New-SourceEditHookOutput([string]) -> [string]`, and `Invoke-SourceEditHook() -> [int]`.

- [ ] **Step 1: Write the failing classifier contract test**

Create `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1` with this initial content:

```powershell
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0

function Test-Contract {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { $script:Pass++; Write-Host "[PASS] $Name" }
    else { $script:Fail++; Write-Host "[FAIL] $Name $Detail" }
}

$hookScript = Join-Path $Root '.codex\hooks\source_edit_triage.ps1'
Test-Contract 'hook classifier exists' (Test-Path -LiteralPath $hookScript)

if (Test-Path -LiteralPath $hookScript) {
    . $hookScript -LibraryMode

    $cases = @(
        [pscustomobject]@{ name='english java edit'; prompt='Please fix the null handling in main/java/com/example/lms/Foo.java.'; expected=$true },
        [pscustomobject]@{ name='korean java edit'; prompt='main/java/com/example/lms/Foo.java 코드를 수정해 주세요.'; expected=$true },
        [pscustomobject]@{ name='review then edit'; prompt='Review and then patch app/src/main/java_clean/Foo.java.'; expected=$true },
        [pscustomobject]@{ name='read only audit'; prompt='main/java 실패 원인만 분석하고 소스는 수정하지 마세요.'; expected=$false },
        [pscustomobject]@{ name='review only'; prompt='Review the code changes without editing files.'; expected=$false },
        [pscustomobject]@{ name='markdown only'; prompt='Update AGENTS.md documentation only.'; expected=$false },
        [pscustomobject]@{ name='test only'; prompt='Run the focused tests and report the result.'; expected=$false }
    )
    foreach ($case in $cases) {
        Test-Contract $case.name ((Test-SourceEditIntent -Prompt $case.prompt) -eq $case.expected)
    }

    $sensitivePrompt = 'Please patch main/java/Foo.java marker-do-not-reflect.'
    $output = New-SourceEditHookOutput -Prompt $sensitivePrompt
    $outputBytes = [Text.Encoding]::UTF8.GetByteCount($output)
    $parsed = $output | ConvertFrom-Json
    Test-Contract 'trigger output is bounded' ($outputBytes -le 1024) $outputBytes
    Test-Contract 'trigger output names UserPromptSubmit' ([string]$parsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit')
    Test-Contract 'trigger output routes to preflight skill' ([string]$parsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
    Test-Contract 'trigger output carries reason code' ([string]$parsed.hookSpecificOutput.additionalContext -match 'source-edit-intent')
    Test-Contract 'raw prompt is not reflected' (-not $output.Contains('marker-do-not-reflect'))
    Test-Contract 'non-trigger output is empty' ([string]::IsNullOrEmpty((New-SourceEditHookOutput -Prompt 'Analyze main/java only; do not edit.')))
}

Write-Host "passed=$script:Pass failed=$script:Fail"
if ($script:Fail -gt 0) { exit 1 }
exit 0
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `1` with `[FAIL] hook classifier exists`; no other repository file changes.

- [ ] **Step 3: Implement the minimal classifier**

Create `.codex/hooks/source_edit_triage.ps1` with this implementation:

```powershell
[CmdletBinding()]
param([switch]$LibraryMode)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MaxInputBytes = 65536
$script:MaxOutputBytes = 1024
$script:AdditionalContext = 'triggerReason=source-edit-intent; Before any application-source mutation, use $demo1-source-edit-three-way-preflight. Do not mutate source unless NEUTRAL_QUERY returns APPLY and the existing source-owner guard passes.'

function Test-SourceEditIntent {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$Prompt)

    if ([string]::IsNullOrWhiteSpace($Prompt)) { return $false }

    $mutationPattern = '(?i)(\bimplement\b|\bmodify\b|\bpatch\b|\bfix\b|\bedit\b|\badd\b|\bremove\b|\brefactor\b|\bupdate\b|\bchange\b|구현(?:해|하|을)|수정(?:해|하|을)|패치(?:해|하|를)|고쳐|변경(?:해|하)|추가(?:해|하)|삭제(?:해|하)|리팩터(?:해|하))'
    $sourcePattern = '(?i)(application[ -]?source|source[ -]?code|main[\\/]|app[\\/]src[\\/]|\.java\b|\.kt\b|\.kts\b|\.py\b|\.ts\b|\.tsx\b|소스[ ]?코드|애플리케이션[ ]?소스)'
    $readOnlyPattern = '(?i)(read[ -]?only|do not edit|without editing|analysis only|analy[sz]e only|review only|audit only|plan only|수정하지 마|편집하지 마|분석만|검토만|감사만|계획만|읽기[ -]?전용)'
    $explicitMutationPattern = '(?i)(please[ ]+(implement|modify|patch|fix|edit|add|remove|refactor|update|change)|then[ ]+(implement|modify|patch|fix|edit|add|remove|refactor|update|change)|구현해|수정해|패치해|고쳐|변경해|추가해|삭제해|리팩터해|해주세요|해줘)'

    $hasMutation = [regex]::IsMatch($Prompt, $mutationPattern)
    $hasSourceObject = [regex]::IsMatch($Prompt, $sourcePattern)
    if (-not ($hasMutation -and $hasSourceObject)) { return $false }

    $hasReadOnlyBoundary = [regex]::IsMatch($Prompt, $readOnlyPattern)
    $hasExplicitMutation = [regex]::IsMatch($Prompt, $explicitMutationPattern)
    if ($hasReadOnlyBoundary -and -not $hasExplicitMutation) { return $false }
    return $true
}

function New-SourceEditHookOutput {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$Prompt)

    if (-not (Test-SourceEditIntent -Prompt $Prompt)) { return '' }
    $payload = [ordered]@{
        hookSpecificOutput = [ordered]@{
            hookEventName = 'UserPromptSubmit'
            additionalContext = $script:AdditionalContext
        }
    }
    $json = $payload | ConvertTo-Json -Depth 4 -Compress
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt $script:MaxOutputBytes) { return '' }
    return $json
}

function Invoke-SourceEditHook {
    [CmdletBinding()]
    param()

    try {
        $raw = [Console]::In.ReadToEnd()
        if ([Text.Encoding]::UTF8.GetByteCount($raw) -gt $script:MaxInputBytes) { return 0 }
        $event = $raw | ConvertFrom-Json
        $prompt = [string]$event.prompt
        $output = New-SourceEditHookOutput -Prompt $prompt
        if (-not [string]::IsNullOrEmpty($output)) { [Console]::Out.Write($output) }
        return 0
    } catch {
        return 0
    }
}

if (-not $LibraryMode) { exit (Invoke-SourceEditHook) }
```

- [ ] **Step 4: Add malformed and oversized stdin integration checks**

Append inside the test file, before its final summary/exit block:

```powershell
$malformed = '{not-json' | & powershell -NoProfile -ExecutionPolicy Bypass -File $hookScript 2>$null
Test-Contract 'malformed hook JSON emits nothing' ([string]::IsNullOrEmpty(($malformed -join '')))

$oversizedPrompt = 'x' * 66000
$oversizedEvent = @{ prompt = $oversizedPrompt } | ConvertTo-Json -Compress
$oversized = $oversizedEvent | & powershell -NoProfile -ExecutionPolicy Bypass -File $hookScript 2>$null
Test-Contract 'oversized hook event emits nothing' ([string]::IsNullOrEmpty(($oversized -join '')))
```

Move the existing summary/exit block after these checks so every assertion affects the exit code.

- [ ] **Step 5: Run the classifier suite to verify GREEN**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `0`, `failed=0`, all three explicit-edit cases pass, all four non-trigger cases pass, malformed/oversized inputs emit nothing, and no raw marker appears.

- [ ] **Step 6: Prepare the Task 2 commit boundary**

If and only if the user separately authorizes commits, run:

```powershell
git add -- .codex/hooks/source_edit_triage.ps1 scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1
git commit -m "feat: add bounded source edit intent classifier"
```

Otherwise record `commit=not_authorized` and continue without staging.

---

### Task 3: Add the compact three-way preflight skill with contract tests

**Files:**
- Create: `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`
- Create: `.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml`
- Create: `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md`
- Modify: `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`

**Interfaces:**
- Consumes: one immutable UserRequest, one frozen EvidenceSnapshot hash, at most 20 evidence rows, and the declared source target set.
- Produces: exactly one PositivePacket, one NegativePacket, and one NeutralVerdict; only Neutral may return `APPLY|HOLD|REJECT`.
- Produces routing output: `nextWorkflow=existing-source-owner-guard` only for stable APPLY; otherwise `nextWorkflow=none` and one `nextSingleProof`.

- [ ] **Step 1: Add failing skill-file and invariant checks**

Insert before the test summary/exit block:

```powershell
$skillRoot = Join-Path $Root '.agents\skills\demo1-source-edit-three-way-preflight'
$skillFile = Join-Path $skillRoot 'SKILL.md'
$skillMeta = Join-Path $skillRoot 'agents\openai.yaml'
$skillContract = Join-Path $skillRoot 'references\preflight-contract.md'

Test-Contract 'preflight skill exists' (Test-Path -LiteralPath $skillFile)
Test-Contract 'preflight skill metadata exists' (Test-Path -LiteralPath $skillMeta)
Test-Contract 'preflight contract exists' (Test-Path -LiteralPath $skillContract)

if (Test-Path -LiteralPath $skillFile) {
    $skillText = Get-Content -LiteralPath $skillFile -Raw -Encoding UTF8
    Test-Contract 'skill fixes canonical query count' ($skillText.Contains('canonicalQueryCount=3'))
    Test-Contract 'skill names exact roles' ($skillText.Contains('POSITIVE_QUERY') -and $skillText.Contains('NEGATIVE_QUERY') -and $skillText.Contains('NEUTRAL_QUERY'))
    Test-Contract 'skill defaults to logical roles' ($skillText.Contains('single-agent-logical-roles'))
    Test-Contract 'skill forbids fourth reviewer' ($skillText.Contains('no-fourth-reviewer'))
    Test-Contract 'skill retains source owner guard' ($skillText.Contains('existing-source-owner-guard'))
    Test-Contract 'skill excludes read only work' ($skillText.Contains('read-only'))
}
if (Test-Path -LiteralPath $skillContract) {
    $contractText = Get-Content -LiteralPath $skillContract -Raw -Encoding UTF8
    foreach ($marker in @(
        'evidenceRowCountMax=20','evidenceSummaryCharsMax=6000',
        'positivePacketCharsMax=2400','negativePacketCharsMax=2400',
        'neutralPacketCharsMax=1800','automatic-trigger-missing',
        'hook-trust-missing','source-edit-classifier-timeout','snapshot-unfrozen',
        'canonical-query-count-invalid','scenario-coverage-mismatch',
        'order-unstable','tri-preflight-timeout','source-owner-unproven',
        'redaction-failed','token-regression'
    )) {
        Test-Contract "contract marker $marker" ($contractText.Contains($marker))
    }
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `1` with the three missing skill-file failures; Task 2 classifier tests remain green.

- [ ] **Step 3: Create the skill instructions**

Create `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md` with this exact bounded workflow:

````markdown
---
name: demo1-source-edit-three-way-preflight
description: Use whenever Desktop Codex is explicitly asked to implement or modify demo-1 application source, before any source write. Do not use for read-only analysis, review, planning, Markdown-only work, test-only execution, or post-patch adjudication.
---

# Demo1 Source-Edit Three-Way Preflight

Freeze one redacted EvidenceSnapshot and create exactly three logical review
packets before application-source mutation. The default process mode is
`single-agent-logical-roles`; do not spawn mandatory subagents.

## Fixed workflow

1. Confirm Desktop root, branch, dirty-target ownership, index lock, active
   sourceSets, declared targets, applicable source-owner guard, and verification
   commands.
2. Freeze at most 20 evidence rows and one `evidenceSnapshotHash`. Use paths,
   hashes, counts, booleans, timings, and redacted reason codes only.
3. Run `POSITIVE_QUERY` over the frozen snapshot. Create two to four falsifiable
   scenario IDs.
4. Run `NEGATIVE_QUERY` over the same snapshot and exact Positive scenario-ID
   set. Acquire no evidence.
5. Run `NEUTRAL_QUERY` over Positive-Negative and Negative-Positive. Acquire no
   evidence and apply the fixed goal-score formula.
6. Set HOLD when order or decisive-evidence sets differ, score is below 50,
   ownership/verification is missing, or a safety gate fails.
7. On stable APPLY, emit `nextWorkflow=existing-source-owner-guard`; this skill
   never edits source itself. On HOLD/REJECT emit `nextWorkflow=none` and one
   `nextSingleProof`.

## Invariants

```text
canonicalQueryCount=3
processMode=single-agent-logical-roles
no-fourth-reviewer=true
majorityVote=false
neutralMayAcquireEvidence=false
rawPromptStored=false
rawResponseStored=false
largeArtifactMode=path-plus-hash
desktopFinalProof=evidence_needed
```

Read [preflight-contract.md](references/preflight-contract.md) for exact packet
fields, size bounds, score, and failure classes. Route an explicitly authorized
Notebook Y-drive source edit to the existing repository guard; do not create a
second lease, compare-and-swap, PatchDrop, or mutation protocol.

## Stop conditions

Stop with HOLD for `source-owner-unproven`, `dirty-overlap`,
`index-lock-present`, `snapshot-unfrozen`, `scenario-coverage-mismatch`,
`order-unstable`, `canonical-query-count-invalid`, missing verification, or
preflight timeout. Reject on redaction failure or undeclared source write.
````

- [ ] **Step 4: Create the exact packet and budget contract**

Create `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md` with:

````markdown
# Source-Edit Three-Way Preflight Contract

## Bounds

```text
canonicalQueryCount=3
evidenceRowCountMax=20
evidenceSummaryCharsMax=6000
positivePacketCharsMax=2400
negativePacketCharsMax=2400
neutralPacketCharsMax=1800
preflightWallClockTargetSeconds=120
```

All packets carry the same `evidenceSnapshotHash`. No role writes files, calls a
provider, dispatches a tool, or fills missing evidence.

## POSITIVE_QUERY

```text
packetType=POSITIVE_QUERY
candidateGoal
scenarioWorlds[2..4]: scenarioId, premise, causalMechanism,
expectedObservation, evidenceNeeded, falsifier
validatedAssumptions
reusableAssets
expectedUserValue
minimalVerification
evidenceIds
unknowns
```

## NEGATIVE_QUERY

```text
packetType=NEGATIVE_QUERY
challengedGoal
scenarioAttacks: scenarioId, counterExample, alternativeCause,
boundaryOrAuthorityRisk, costAndBlastRadius, smallestDisconfirmingProbe,
evidenceIds
falsifiers
missingEvidence
safetyRisks
```

The Negative scenario-ID set must exactly equal the Positive set. A mismatch is
`scenario-coverage-mismatch` and forces HOLD.

## NEUTRAL_QUERY

```text
packetType=NEUTRAL_QUERY
forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict
reverseVerdict
forwardDecisiveEvidenceIds
reverseDecisiveEvidenceIds
orderStable
verdict=APPLY|HOLD|REJECT
selectedOrRewrittenGoal
goalScore
decisiveEvidence
rejectedClaims
nextSingleProof
confidence=L|M|H
```

Compute and clamp:

```text
100 * (0.25*evidenceStrength + 0.20*causalStrength
+ 0.15*verificationFeasibility + 0.15*userValue + 0.10*reversibility
+ 0.10*costEfficiency + 0.05*timeFit - 0.20*blastRadius
- 0.15*ambiguity - 0.20*authorityOrSafetyExpansion)
```

`order-unstable`, score below 50, `source-owner-unproven`, missing verification,
or any safety-gate failure forces HOLD. Redaction failure or undeclared source
write forces REJECT. APPLY routes to `existing-source-owner-guard`; it is not a
source mutation.

## Failure classes

```text
automatic-trigger-missing=report hook absence; do not claim automatic coverage
hook-trust-missing=HOLD automatic-coverage claim until Desktop trusts exact hash
source-edit-classifier-timeout=no hook authorization; source mutation remains gated
snapshot-unfrozen=HOLD
canonical-query-count-invalid=HOLD
scenario-coverage-mismatch=HOLD
order-unstable=HOLD
tri-preflight-timeout=HOLD
source-owner-unproven=HOLD
redaction-failed=REJECT and discard unsafe artifact
token-regression=keep rollout demo-1-local; do not expand
```
````

- [ ] **Step 5: Add skill metadata**

Create `.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml` with:

```yaml
interface:
  display_name: "Demo1 Source-Edit Three-Way Preflight"
  short_description: "Bound source edits to three review roles"
  default_prompt: "Use $demo1-source-edit-three-way-preflight before any explicitly authorized demo-1 application-source mutation."
```

- [ ] **Step 6: Run the combined classifier and skill tests to verify GREEN**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `0`, `failed=0`, all classifier tests remain green, all three skill files exist, every query/bound/failure marker is present.

- [ ] **Step 7: Prepare the Task 3 commit boundary**

If and only if the user separately authorizes commits, run:

```powershell
git add -- .agents/skills/demo1-source-edit-three-way-preflight scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1
git commit -m "feat: add source edit three way preflight skill"
```

Otherwise record `commit=not_authorized` and continue without staging.

---

### Task 4: Register the project hook and durable AGENTS routing

**Files:**
- Create: `.codex/hooks.json`
- Modify: `AGENTS.md:67`
- Modify: `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`

**Interfaces:**
- Consumes: the classifier path from Task 2 and the skill name from Task 3.
- Produces: one project-local `UserPromptSubmit` command handler and one durable policy line; neither contains the packet body.

- [ ] **Step 1: Add failing hook-config and AGENTS integration checks**

Insert before the test summary/exit block:

```powershell
$hooksFile = Join-Path $Root '.codex\hooks.json'
$agentsFile = Join-Path $Root 'AGENTS.md'
Test-Contract 'project hooks config exists' (Test-Path -LiteralPath $hooksFile)

if (Test-Path -LiteralPath $hooksFile) {
    $hooks = Get-Content -LiteralPath $hooksFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $promptGroups = @($hooks.hooks.UserPromptSubmit)
    $handlers = @($promptGroups | ForEach-Object { @($_.hooks) } | ForEach-Object { $_ })
    Test-Contract 'exactly one UserPromptSubmit handler' ($handlers.Count -eq 1) $handlers.Count
    if ($handlers.Count -eq 1) {
        $handler = $handlers[0]
        Test-Contract 'hook handler is command' ([string]$handler.type -ceq 'command')
        Test-Contract 'hook timeout is two seconds' ([int]$handler.timeout -eq 2)
        Test-Contract 'hook context limit is 512' ([int]$handler.additionalContextLimit -eq 512)
        Test-Contract 'hook command is repository relative' (-not ([string]$handler.commandWindows -match '^[A-Za-z]:|^\\\\'))
        Test-Contract 'hook command names classifier' ([string]$handler.commandWindows -match 'source_edit_triage\.ps1')
    }
    Test-Contract 'UserPromptSubmit has no matcher' (-not ($promptGroups[0].PSObject.Properties.Name -contains 'matcher'))
}

if (Test-Path -LiteralPath $agentsFile) {
    $agentsText = Get-Content -LiteralPath $agentsFile -Raw -Encoding UTF8
    $routingMarker = 'Use `$demo1-source-edit-three-way-preflight` before any explicitly requested application-source mutation'
    Test-Contract 'AGENTS contains one preflight routing rule' (([regex]::Matches($agentsText, [regex]::Escape($routingMarker))).Count -eq 1)
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `1` because `.codex/hooks.json` and the AGENTS routing marker are absent; Task 2 and Task 3 assertions remain green.

- [ ] **Step 3: Register the project-local command hook**

Create `.codex/hooks.json` with:

```json
{
  "description": "Bound explicit demo-1 source edits to the repo-local three-way preflight.",
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "powershell -NoProfile -ExecutionPolicy Bypass -File .\\.codex\\hooks\\source_edit_triage.ps1",
            "commandWindows": "powershell -NoProfile -ExecutionPolicy Bypass -File .\\.codex\\hooks\\source_edit_triage.ps1",
            "timeout": 2,
            "statusMessage": "Checking source-edit preflight",
            "additionalContextLimit": 512
          }
        ]
      }
    ]
  }
}
```

Do not add a matcher, prompt handler, agent handler, `PreToolUse` handler, absolute path, background process, or asynchronous option.

- [ ] **Step 4: Add the single durable AGENTS routing rule**

Under `## Reusable Prompt Packs`, immediately after the existing `@superpowers` routing line, insert exactly:

```markdown
- Use `$demo1-source-edit-three-way-preflight` before any explicitly requested application-source mutation; freeze one redacted EvidenceSnapshot, run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`, and enter the existing source-owner guard only after a stable `APPLY`. Do not trigger it for read-only, Markdown-only, or test-only work.
```

Do not copy the packet schema or token budgets into `AGENTS.md`.

- [ ] **Step 5: Run contract tests from the Desktop root**

Run:

```powershell
Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `0`, `failed=0`; config parses, one handler exists, no matcher exists, command path is relative, and exactly one AGENTS marker exists.

- [ ] **Step 6: Exercise the registered hook command directly**

Run:

```powershell
$trigger = @{ hookEventName='UserPromptSubmit'; prompt='Please fix main/java/com/example/lms/Foo.java.' } | ConvertTo-Json -Compress
$triggerOutput = $trigger | & powershell -NoProfile -ExecutionPolicy Bypass -File .\.codex\hooks\source_edit_triage.ps1
$triggerParsed = $triggerOutput | ConvertFrom-Json
if ([string]$triggerParsed.hookSpecificOutput.hookEventName -cne 'UserPromptSubmit') { throw 'hook-trigger-output-invalid' }

$nonTrigger = @{ hookEventName='UserPromptSubmit'; prompt='Analyze main/java only and do not edit.' } | ConvertTo-Json -Compress
$nonTriggerOutput = $nonTrigger | & powershell -NoProfile -ExecutionPolicy Bypass -File .\.codex\hooks\source_edit_triage.ps1
if (-not [string]::IsNullOrEmpty(($nonTriggerOutput -join ''))) { throw 'hook-false-trigger' }
```

Expected: trigger output is valid bounded JSON naming the skill; non-trigger output is empty.

- [ ] **Step 7: Review and trust the exact project hook on Desktop**

Open a fresh Desktop Codex session at the canonical root, run `/hooks`, inspect the source path and command hash, and trust only the exact `.codex/hooks.json` definition. Record:

```text
hookSource=project
hookEvent=UserPromptSubmit
hookTrust=trusted
hookHashCapturedFrom=Desktop-/hooks-exact-display
rawPromptStored=false
```

Copy the exact displayed hash into the execution report; do not place it in source.
If the UI does not show a reviewable exact hash or the project is untrusted, stop
with `hook-trust-missing` and do not claim automatic hook coverage.

- [ ] **Step 8: Prepare the Task 4 commit boundary**

If and only if the user separately authorizes commits, run:

```powershell
git add -- .codex/hooks.json AGENTS.md scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1
git commit -m "feat: route source edits through three way preflight"
```

Otherwise record `commit=not_authorized` and continue without staging.

---

### Task 5: Run focused verification and hold costly measurement behind approval

**Files:**
- Verify only: the six created files and one modified `AGENTS.md` declared in this plan.
- No new source-controlled file in this task.

**Interfaces:**
- Consumes: completed Tasks 1-4, Desktop hook trust evidence, declared target list.
- Produces: bounded command results, hashes, count-only secret result, mutation-surface result, `artifactVerdict`, `tokenEfficiencyVerdict`, and `desktopFinalProof`.

- [ ] **Step 1: Run the new focused contract suite**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `0`, `failed=0`.

- [ ] **Step 2: Run existing three-way regressions**

Run:

```powershell
python .\scripts\test_three_perspective_chat_postprocess.py
python .\scripts\score_three_way_long_tail_design.py --input .\agent-prompts\agents\demo1_three_perspective_chat_postprocess\v2_design_contract.json
```

Expected: 14 tests pass; design grader emits `designVerdict=APPLY`, `metaScore=100`, `falseAcceptCount=0`, `canonicalFalseRejectCount=0`, and `observedEvidence.secretPatternHitCount=0`.

- [ ] **Step 3: Run the compact skill-family validator**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
```

Expected: the new skill has valid frontmatter, references, metadata, and line/word budgets. Read the compact fields first. Do not open a full report when `fullReportRecommended=false`.

- [ ] **Step 4: Run a count-only secret scan over the declared files**

Run:

```powershell
$declaredFiles = @(
    '.codex\hooks\source_edit_triage.ps1',
    '.codex\hooks.json',
    '.agents\skills\demo1-source-edit-three-way-preflight\SKILL.md',
    '.agents\skills\demo1-source-edit-three-way-preflight\agents\openai.yaml',
    '.agents\skills\demo1-source-edit-three-way-preflight\references\preflight-contract.md',
    'scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1',
    'AGENTS.md'
)
$patterns = @(
    'sk-[A-Za-z0-9_-]{16,}',
    '(?i)authorization\s*:\s*bearer\s+[A-Za-z0-9._-]+',
    '(?i)(api[_-]?key|client[_-]?secret|owner[_-]?token)\s*=\s*[^\s`"]+'
)
$secretPatternHitCount = 0
foreach ($file in $declaredFiles) {
    $text = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    foreach ($pattern in $patterns) { $secretPatternHitCount += [regex]::Matches($text, $pattern).Count }
}
Write-Host "secretPatternHitCount=$secretPatternHitCount"
if ($secretPatternHitCount -ne 0) { exit 1 }
```

Expected: `secretPatternHitCount=0`. Never print matching content.

- [ ] **Step 5: Prove the mutation surface did not expand**

Run:

```powershell
$status = @(git status --short)
$forbidden = @($status | Where-Object {
    $path = ([string]$_).Substring([Math]::Min(3, ([string]$_).Length)).Trim().Replace('\','/')
    $path -match '^(main/|app/|src/test/|build\.gradle|settings\.gradle|gradle/)'
})
Write-Host "forbiddenChangedPathCount=$($forbidden.Count)"
if ($forbidden.Count -ne 0) { exit 1 }
```

Expected: `forbiddenChangedPathCount=0`. Preserve unrelated pre-existing dirty files; do not revert or stage them.

- [ ] **Step 6: Record the focused artifact verdict**

After Steps 1-5 and Desktop hook trust pass, report:

```text
artifactVerdict=APPLY
canonicalQueryCount=3
hookClassifierVerdict=APPLY
hookTrust=trusted
secretPatternHitCount=0
applicationSourceMutation=false
runtimeLineageVerdict=HOLD
runtimeLineageReason=no-provider-attempt-response-proof
tokenEfficiencyVerdict=INCONCLUSIVE
desktopFinalProof=focused_tooling_pass
```

If hook trust is missing, use `artifactVerdict=HOLD` and `desktopFinalProof=evidence_needed`.

- [ ] **Step 7: Stop before the 20-case token experiment unless model-usage cost is explicitly approved**

The approved design requires at least 20 paired source-edit cases before claiming token savings or expanding beyond `demo-1`. Running those Codex turns consumes model usage and is not implicitly authorized by implementing the tooling. Ask for separate approval to run the locked paired deck.

When approved, keep feature off/on pairs identical in task input, model, reasoning effort, context policy, and verification commands. Record only:

```text
runIdHash
taskDeckId
featureMode=off|on
model
reasoningEffort
subagentCount
triggered
neutralVerdict
token.total
token.input
token.cached_input
token.output
token.reasoning_output
toolCallCount
elapsedMs
focusedVerificationPassed
reworkCount
failureClass
```

Release requires 20 valid pairs, trigger coverage 100%, read-only false-trigger rate at most 5%, paired median total-token reduction at least 20%, no focused-test pass-rate decrease, and no safety-gate bypass. If safe per-turn token metrics are unavailable, report `token-ledger-unavailable`; do not modify global telemetry configuration or infer savings.

After Phase A passes, any Sol/Terra/Luna or reasoning-effort comparison is Phase B
and requires another explicit model-usage approval. Hold the workflow, deck,
verification commands, and context policy fixed; vary only the declared model or
reasoning profile. Do not reuse a Phase-A workflow delta as model-causality evidence.

- [ ] **Step 8: Prepare a final commit boundary without executing it**

If the user separately authorizes a final commit and no task commits were made, the exact allowed command is:

```powershell
git add -- .codex/hooks/source_edit_triage.ps1 .codex/hooks.json .agents/skills/demo1-source-edit-three-way-preflight scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1 AGENTS.md
git commit -m "feat: add bounded source edit three way preflight"
```

Otherwise report `commit=not_authorized`. Never include unrelated files.

---

## Rollback Procedure

Before rollback, confirm each target is one of the six added files or the single
`AGENTS.md` routing line. Do not use recursive deletion.

1. Remove the exact project hook trust entry through `/hooks` if Codex still shows it.
2. Remove only these added files:
   - `.codex/hooks/source_edit_triage.ps1`
   - `.codex/hooks.json`
   - `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`
   - `.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml`
   - `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md`
   - `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`
3. Remove the single exact `AGENTS.md` routing bullet added in Task 4.
4. Open a fresh Codex session so skill and hook discovery refreshes.
5. Confirm `.codex/hooks.json` is absent, the routing marker count is zero, and no
   application-source file changed.

No application source, database, provider, Gradle, credential, deployment, SMB
mapping, or PatchDrop rollback is required.

## Completion Boundary

Implementation is complete only when Tasks 1-5 Steps 1-6 pass on the Desktop
canonical checkout and the exact hook is trusted. This proves the bounded tooling
artifact, not token savings. `tokenEfficiencyVerdict` remains `INCONCLUSIVE` until
the separately approved 20-pair experiment passes every release gate. Global rollout,
automatic model routing, mandatory subagents, or a `PreToolUse` hard gate requires a
new approved design.
