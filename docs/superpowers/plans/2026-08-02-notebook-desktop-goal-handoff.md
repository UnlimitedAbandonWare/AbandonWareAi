# Notebook–Desktop Goal Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fail-closed Notebook-to-Desktop goal handoff with a PowerShell-compatible Y-drive identity probe, one manifest-built controller prompt, and current three-node SMB policy wording.

**Architecture:** A small PowerShell probe acquires only the live mapping identity decision, while the existing Python Y-drive autograder continues to own routing judgment. A new repository prompt composes existing Notebook goal/triad skills and hands validated contracts to Desktop; the existing three-node prompt is narrowed to current `Y:\` policy, and the existing manifest builder remains the only generated-output owner.

**Tech Stack:** Windows PowerShell 5.1-compatible syntax, Python 3 `unittest`, PyYAML, UTF-8 Markdown/YAML, existing `agent-prompts/build.py`, existing Y-drive policy autograder, Gradle wrapper for Desktop governance proof.

## Global Constraints

- Request class is `prompt_skill_tooling_only`; do not edit application source or resources.
- Canonical Notebook workspace remains exactly `Y:\`.
- Repository-owned expected backing identity remains exactly `30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9`.
- Public identity output contains only `canonicalWorkspace`, `backingShareIdentityVerified`, and `backingShareIdentityReason`.
- Never print or persist the raw mapping, normalized mapping, actual computed hash, UNC Git root, credentials, headers, cookies, DB URLs, or environment dumps.
- Use `SHA256.Create().ComputeHash()`; prohibit `SHA256.HashData` for Windows PowerShell 5.1 compatibility.
- Reuse `notebook-goal-directive-generator`, `notebook-smb-network-workspace`, `tri-query-cloud-router`, and `scripts/ydrive_smb_workspace_policy_autograder.py`; do not copy their implementations.
- Exactly three decision roles exist when literal subagents are requested: `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`. There is no majority vote or fourth judge.
- Supabase is optional, project-scoped, and read-only; unproven scope performs zero project calls and zero mutation.
- Desktop owns final prompt/tooling mutation and proof. Notebook evidence remains supporting evidence and keeps `desktopFinalProof=evidence_needed` until Desktop verification runs.
- Do not change public APIs, Gradle dependencies, DB/DDL, credentials, environment variable names, or secret flow.
- Do not commit, push, deploy, or send external messages unless the user separately authorizes that action. Each task ends at a review checkpoint.

---

## File Structure

### Create

- `scripts/verify_ydrive_backing_identity.ps1` — acquire and redact one live mapping identity decision.
- `scripts/verify_ydrive_backing_identity_contract_tests.ps1` — test compatible hashing, bounded output, match/mismatch/evidence-needed states, and non-disclosure.
- `scripts/test_notebook_desktop_goal_handoff_prompt.py` — statically test the new prompt, manifest entry, AGENTS pointer, and aligned three-node policy.
- `agent-prompts/agents/demo1_notebook_desktop_goal_handoff/meta.yaml` — package metadata and owned source declaration.
- `agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md` — controller/handoff contract.

### Modify

- `agent-prompts/prompts.manifest.yaml:210-218` — register the new prompt beside the current three-node prompt.
- `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md:18-114` — replace legacy Notebook mode/root rules.
- `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md:235-304` — remove raw network-path handoff examples and timestamp/FIFO selection.
- `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md:445-488` — replace NAS transition examples with canonical/mode-neutral rules.
- `AGENTS.md:73-82` — add one short handoff-prompt and compatible-probe pointer.

### Generated, never hand-edit

- `agent-prompts/out/demo1_notebook_desktop_goal_handoff.prompt`
- `agent-prompts/out/demo1_three_node_smb_codex.prompt`

### Explicitly excluded

- `main/java/**`, `main/resources/**`
- `app/src/main/java_clean/**`, `app/src/main/resources/**`
- `project/**`, `demo-1/**`, `lms-core/**`
- archives, backups, DB/DDL, secret/config files, and generated build outputs

---

### Task 1: Add the PowerShell-Compatible Y-Drive Identity Probe

**Files:**
- Create: `scripts/verify_ydrive_backing_identity.ps1`
- Create: `scripts/verify_ydrive_backing_identity_contract_tests.ps1`
- Existing integration check: `scripts/ydrive_smb_workspace_policy_autograder.py`

**Interfaces:**
- Consumes: `-CanonicalWorkspace 'Y:\'` and `-ExpectedSha256` containing exactly 64 hexadecimal characters; optional hidden `-MappingResolver` accepts a PowerShell script block only for deterministic contract tests.
- Produces: one compact JSON object with exactly `canonicalWorkspace: string`, `backingShareIdentityVerified: bool`, and `backingShareIdentityReason: match|mismatch|evidence-needed`.
- Invariant: the probe supplies identity evidence only; it never returns `sourceWriteRoot`, `authorizedMutation`, mode, or verdict.

- [ ] **Step 0: Capture rollback preimages before the first implementation edit**

Run once from `Y:\` before creating or modifying any implementation target:

```powershell
$PreimageRoot = Join-Path ([IO.Path]::GetTempPath()) 'awx-notebook-desktop-handoff-preimage'
if (Test-Path -LiteralPath $PreimageRoot) {
    throw 'rollback-preimage-already-exists'
}

$RequiredPreimages = @(
    'AGENTS.md',
    'agent-prompts\prompts.manifest.yaml',
    'agent-prompts\agents\demo1_three_node_smb_codex\system_ko.md'
)
$GeneratedRelativePath = 'agent-prompts\out\demo1_three_node_smb_codex.prompt'
$GeneratedAbsentMarker = Join-Path $PreimageRoot 'absent\agent-prompts\out\demo1_three_node_smb_codex.prompt.absent'

foreach ($RelativePath in $RequiredPreimages) {
    if (-not (Test-Path -LiteralPath $RelativePath -PathType Leaf)) {
        throw "rollback-preimage-missing:$RelativePath"
    }
    $Destination = Join-Path $PreimageRoot $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    Copy-Item -LiteralPath $RelativePath -Destination $Destination
}

$GeneratedSource = Join-Path (Get-Location) $GeneratedRelativePath
$GeneratedDestination = Join-Path $PreimageRoot $GeneratedRelativePath
if (Test-Path -LiteralPath $GeneratedSource -PathType Leaf) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $GeneratedDestination) | Out-Null
    Copy-Item -LiteralPath $GeneratedSource -Destination $GeneratedDestination
    $GeneratedPreimageState = 'present'
} else {
    New-Item -ItemType Directory -Force -Path $GeneratedAbsentMarker | Out-Null
    $GeneratedPreimageState = 'absent'
}

$PreimageFiles = @($RequiredPreimages | ForEach-Object { Join-Path $PreimageRoot $_ })
if ($GeneratedPreimageState -eq 'present') {
    $PreimageFiles += $GeneratedDestination
}
Get-FileHash -Algorithm SHA256 $PreimageFiles
[pscustomobject]@{ generatedOutputPreimage = $GeneratedPreimageState }
```

Expected: three required SHA-256 rows, an optional fourth row only when the prior generated output exists, one `generatedOutputPreimage=present|absent` state, and no live checkout mutation. A missing generated output is a valid `absent` preimage; a missing required source file remains `rollback-preimage-unproven`. If the fixed preimage directory already exists, stop without overwriting it.

- [ ] **Step 1: Write the failing probe contract test**

Create `scripts/verify_ydrive_backing_identity_contract_tests.ps1` with these literal cases:

```powershell
$ErrorActionPreference = 'Stop'
$Probe = Join-Path $PSScriptRoot 'verify_ydrive_backing_identity.ps1'

function Get-TestSha256([string]$Value) {
    $normalized = $Value.Trim().TrimEnd('\').ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized))
        )).Replace('-', '').ToUpperInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-TestProbe([string]$MappingValue, [string]$Expected) {
    $resolver = { param([string]$DriveName) $MappingValue }.GetNewClosure()
    $json = & $Probe -CanonicalWorkspace 'Y:\' -ExpectedSha256 $Expected -MappingResolver $resolver
    return [pscustomobject]@{ Raw = [string]$json; Value = $json | ConvertFrom-Json }
}

$mapping = 'SyntheticBackingRoot\'
$expected = Get-TestSha256 $mapping
$match = Invoke-TestProbe $mapping $expected
$keys = @($match.Value.PSObject.Properties.Name | Sort-Object)
Assert-True (($keys -join ',') -eq 'backingShareIdentityReason,backingShareIdentityVerified,canonicalWorkspace') 'unexpected-output-fields'
Assert-True ($match.Value.canonicalWorkspace -eq 'Y:\') 'wrong-canonical-workspace'
Assert-True ($match.Value.backingShareIdentityVerified -eq $true) 'match-not-verified'
Assert-True ($match.Value.backingShareIdentityReason -eq 'match') 'wrong-match-reason'
Assert-True (-not $match.Raw.Contains($mapping)) 'raw-mapping-leaked'
Assert-True (-not $match.Raw.Contains($expected)) 'actual-or-expected-hash-leaked'

$mismatch = Invoke-TestProbe $mapping ('0' * 64)
Assert-True ($mismatch.Value.backingShareIdentityVerified -eq $false) 'mismatch-verified'
Assert-True ($mismatch.Value.backingShareIdentityReason -eq 'mismatch') 'wrong-mismatch-reason'

$missing = Invoke-TestProbe '' $expected
Assert-True ($missing.Value.backingShareIdentityVerified -eq $false) 'missing-verified'
Assert-True ($missing.Value.backingShareIdentityReason -eq 'evidence-needed') 'wrong-missing-reason'

# Add warning, information, host, verbose, debug, nonterminating-error,
# throwing-resolver, and multiple-success-value cases. Capture all streams at
# the probe boundary and assert exactly one JSON object, exactly the three
# public fields, evidence-needed, and zero sentinel disclosure for each case.

$source = Get-Content -Raw -LiteralPath $Probe
Assert-True (-not $source.Contains('SHA256]::HashData')) 'unsupported-hashdata-api'
Assert-True ($source.Contains('SHA256]::Create')) 'compatible-hash-api-missing'
Write-Host 'PASS verify_ydrive_backing_identity_contract_tests failures=0'
```

- [ ] **Step 2: Run the contract test and verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity_contract_tests.ps1
```

Expected: non-zero exit because `scripts/verify_ydrive_backing_identity.ps1` does not exist.

- [ ] **Step 3: Implement the minimal compatible probe**

Create `scripts/verify_ydrive_backing_identity.ps1` with this structure:

```powershell
param(
    [string]$CanonicalWorkspace = 'Y:\',
    [Parameter(Mandatory = $true)]
    [string]$ExpectedSha256,
    [Parameter(DontShow = $true)]
    [scriptblock]$MappingResolver = {
        param([string]$DriveName)
        (Get-PSDrive -Name $DriveName -ErrorAction Stop).DisplayRoot
    }
)

$verified = $false
$reason = 'evidence-needed'

try {
    if ($CanonicalWorkspace -ne 'Y:\' -or $ExpectedSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'invalid-probe-input'
    }
    $driveName = $CanonicalWorkspace.TrimEnd('\').TrimEnd(':')
    $resolverRecords = @(& $MappingResolver $driveName *>&1)
    # Classify ErrorRecord, WarningRecord, VerboseRecord, DebugRecord, and
    # InformationRecord without stringifying them. Any auxiliary record, or
    # anything other than exactly one success string, fails closed.
    $mapping = Get-ExactlyOneSuccessStringOrThrow $resolverRecords
    if ([string]::IsNullOrWhiteSpace($mapping)) { throw 'mapping-unavailable' }
    $normalized = $mapping.Trim().TrimEnd('\').ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $actual = ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized))
        )).Replace('-', '').ToUpperInvariant()
    } finally {
        $sha.Dispose()
    }
    $verified = $actual -eq $ExpectedSha256.ToUpperInvariant()
    $reason = if ($verified) { 'match' } else { 'mismatch' }
} catch {
    $verified = $false
    $reason = 'evidence-needed'
}

[pscustomobject][ordered]@{
    canonicalWorkspace = 'Y:\'
    backingShareIdentityVerified = $verified
    backingShareIdentityReason = $reason
} | ConvertTo-Json -Compress
```

Do not log `$mapping`, `$normalized`, `$actual`, exception text, or any auxiliary resolver record.

- [ ] **Step 4: Run the probe tests and verify GREEN**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity_contract_tests.ps1
python -X utf8 scripts\ydrive_smb_workspace_policy_autograder.py --self-test
```

Expected:

```text
PASS verify_ydrive_backing_identity_contract_tests failures=0
... failures=0
```

- [ ] **Step 5: Verify the live bounded output**

Run:

```powershell
$Expected = '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity.ps1 -CanonicalWorkspace 'Y:\' -ExpectedSha256 $Expected
```

Expected JSON values: `canonicalWorkspace=Y:\`, `backingShareIdentityVerified=true`, `backingShareIdentityReason=match`; no fourth field.

- [ ] **Step 6: Review checkpoint**

Inspect only the two new probe files and test output. Confirm the probe grants no authority and emits no raw mapping/hash. Do not commit.

---

### Task 2: Add the Notebook–Desktop Handoff Prompt and Manifest Entry

**Files:**
- Create: `scripts/test_notebook_desktop_goal_handoff_prompt.py`
- Create: `agent-prompts/agents/demo1_notebook_desktop_goal_handoff/meta.yaml`
- Create: `agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md`
- Modify: `agent-prompts/prompts.manifest.yaml:210-218`
- Generate: `agent-prompts/out/demo1_notebook_desktop_goal_handoff.prompt`

**Interfaces:**
- Consumes: classified UserRequest, three-field identity result, one frozen EvidenceSnapshot/hash, and literal role provenance when required.
- Produces: terminal `TriadExecutionStatus` on incomplete literal roles, otherwise the three canonical packets followed by `GoalContract` and `SourceDirective`.
- Downstream contract: Desktop receives `sourceOwner=desktop` and never receives inferred root, branch, target, or success evidence.

- [ ] **Step 1: Write failing prompt-package tests**

Create `scripts/test_notebook_desktop_goal_handoff_prompt.py`:

```python
from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
PROMPT = ROOT / "agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md"
META = ROOT / "agent-prompts/agents/demo1_notebook_desktop_goal_handoff/meta.yaml"
MANIFEST = ROOT / "agent-prompts/prompts.manifest.yaml"
AGENTS = ROOT / "AGENTS.md"
THREE_NODE = ROOT / "agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md"


class HandoffPromptTests(unittest.TestCase):
    def test_manifest_registers_one_system_only_prompt(self) -> None:
        manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
        rows = [row for row in manifest["agents"] if row["id"] == "demo1_notebook_desktop_goal_handoff"]
        self.assertEqual(1, len(rows))
        row = rows[0]
        self.assertEqual("agents/demo1_notebook_desktop_goal_handoff/system_ko.md", row["system"])
        self.assertEqual([], row["traits"])
        self.assertEqual(["system"], row["merge"]["order"])
        self.assertEqual("out/demo1_notebook_desktop_goal_handoff.prompt", row["output"]["path"])

    def test_prompt_contains_literal_triad_and_contract_boundaries(self) -> None:
        text = PROMPT.read_text(encoding="utf-8")
        required = (
            "requiresLiteralSubagents=true",
            "processMode=three-subagents",
            "actualAgentCount",
            "POSITIVE_QUERY",
            "scenarioWorlds[2..4]",
            "causalMechanism",
            "counterExample",
            "NEGATIVE_QUERY",
            "scenarioAttacks",
            "alternativeCause",
            "boundaryOrAuthorityRisk",
            "smallestDisconfirmingProbe",
            "NEUTRAL_QUERY",
            "forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]",
            "reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]",
            "forwardDecisiveEvidenceIds",
            "reverseDecisiveEvidenceIds",
            "scoreInputs",
            "runtimeLineageVerdict",
            "GoalContract",
            "authorizedMutationSurface",
            "verificationOwner",
            "stopConditions",
            "SourceDirective",
            "activeSourceSets",
            "callPathOrBoundary",
            "patchdropContract",
            "canonicalWorkspace=Y:\\",
            "sourceWriteRoot=null",
            "authorizedMutation=false",
            "desktopFinalProof=evidence_needed",
            "supabase-project-scope-unproven",
        )
        self.assertEqual([], [token for token in required if token not in text])
        self.assertNotRegex(text, re.compile(r"\\\\[A-Za-z0-9._-]+\\"))
        self.assertNotIn("MACSRC_SMB_DIRECT", text)

    # Also assert exact meta.yaml values, manifest-derived equality for both
    # generated outputs, literal triad section order, and controller schema
    # equality against the imported validator constants. Exercise one valid end-to-end validator fixture,
    # one order-unstable rejection, and one below-50 rejection from bounded
    # temporary JSON only.

    def test_incomplete_literal_triad_is_terminal(self) -> None:
        text = PROMPT.read_text(encoding="utf-8")
        self.assertIn("TriadExecutionStatus", text)
        self.assertIn("literal-subagents-unavailable", text)
        self.assertIn("triad-role-failed", text)
        self.assertIn("GoalContract와 SourceDirective를 출력하지 않는다", text)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the prompt test and verify RED**

Run:

```powershell
python -B -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py -v
```

Expected: failures for missing prompt files and manifest entry.

- [ ] **Step 3: Add package metadata**

Create `agent-prompts/agents/demo1_notebook_desktop_goal_handoff/meta.yaml`:

```yaml
id: demo1_notebook_desktop_goal_handoff
version: "1.0.0"
language: ko
scope: notebook-readonly-goal-to-desktop-prompt-tooling-handoff
system: system_ko.md
manifest_registered: true
owner: desktop
```

- [ ] **Step 4: Add the bounded controller prompt**

Create `system_ko.md` with these exact sections and contracts:

```markdown
# Demo-1 Notebook–Desktop Goal Handoff

## 역할

Notebook은 읽기, EvidenceSnapshot, 목표 평가, GoalContract, SourceDirective만 소유한다.
Desktop은 canonical prompt/tooling 수정과 최종 검증을 소유한다.
애플리케이션 소스, DB, credential, commit, push, deploy는 이 프롬프트의 mutation surface가 아니다.

## Y-drive 증거

canonicalWorkspace=Y:\
읽기/감사에서는 sourceWriteRoot=null, authorizedMutation=false다.
identity 결과는 canonicalWorkspace, backingShareIdentityVerified, backingShareIdentityReason 세 필드만 허용한다.
mismatch 또는 evidence-needed는 mutation HOLD이며 fallback workspace를 만들지 않는다.

## Literal triad

명시적 subagent 요청이면 requiresLiteralSubagents=true, processMode=three-subagents다.
POSITIVE_QUERY가 scenarioId를 봉인하고 NEGATIVE_QUERY가 각 ID를 정확히 한 번 공격한 뒤 NEUTRAL_QUERY가 A-B와 B-A를 비교한다.
실제 완료 역할만 actualAgentCount에 포함한다. 누락/실패 시 TriadExecutionStatus만 출력하고 GoalContract와 SourceDirective를 출력하지 않는다.
failureClass는 literal-subagents-unavailable 또는 triad-role-failed다.
다수결, 네 번째 심판, 새 Neutral 증거는 금지한다.

## 정상 출력

완전한 triad와 validator PASS 뒤에만 GoalContract와 SourceDirective를 출력한다.
Desktop root, branch, dirty status, target, verification이 없으면 해당 필드는 evidence_needed다.
SourceDirective는 sourceOwner=desktop, publicApiChange=forbidden, secretMutation=forbidden, desktopFinalProof=evidence_needed를 유지한다.

## Supabase

project scope와 read-only authority가 없으면 supabase-project-scope-unproven을 기록하고 프로젝트 호출을 하지 않는다.
DB, Auth, Storage, Edge Function, migration, credential mutation은 금지한다.
```

Continue the same `system_ko.md` with the exact schemas from `scripts\validate_goal_directive_packets.py` and `goal-contract.md`; do not rename, omit, or add packet fields in this controller:

```text
## Canonical packet schemas

RunArtifact
- schemaVersion
- userRequest
- evidenceSnapshot
- evidenceSnapshotHash
- requiresLiteralSubagents
- processMode
- actualAgentCount
- packets

EvidenceSnapshot
- summary
- evidenceRows

EvidenceRow
- evidenceId
- owner
- observedAt
- observation
- verificationCommand

PositivePacket (maximum 2400 canonical JSON characters)
- packetType=POSITIVE_QUERY
- evidenceSnapshotHash
- candidateGoal
- scenarioWorlds[2..4]
  - scenarioId
  - premise
  - causalMechanism
  - expectedObservation
  - evidenceNeeded
  - falsifier
- validatedAssumptions
- reusableAssets
- expectedUserValue
- minimalVerification
- evidenceIds
- unknowns

NegativePacket (maximum 2400 canonical JSON characters)
- packetType=NEGATIVE_QUERY
- evidenceSnapshotHash
- challengedGoal
- scenarioAttacks
  - scenarioId
  - counterExample
  - alternativeCause
  - boundaryOrAuthorityRisk
  - costAndBlastRadius
  - smallestDisconfirmingProbe
  - evidenceIds
- falsifiers
- counterExamples
- authorityRisks
- safetyRisks
- missingEvidence
- smallestDisconfirmingProbe
- evidenceIds

NeutralVerdict (maximum 1800 canonical JSON characters)
- packetType=NEUTRAL_QUERY
- evidenceSnapshotHash
- forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
- reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
- forwardVerdict
- reverseVerdict
- forwardDecisiveEvidenceIds
- reverseDecisiveEvidenceIds
- orderStable
- verdict: APPLY | HOLD | REJECT
- selectedOrRewrittenGoal
- scoreInputs
- goalScore
- decisiveEvidence
- rejectedClaims
- nextSingleProof
- confidence: L | M | H

ScoreInputs contains exactly evidenceStrength, causalStrength,
verificationFeasibility, userValue, reversibility, costEfficiency, timeFit,
blastRadius, ambiguity, and authorityOrSafetyExpansion. Each input contains
exactly value and evidenceIds.

Run `python scripts\validate_goal_directive_packets.py validate --input <run-artifact.json>`
before emitting contracts. Verdict disagreement or decisive-evidence-set
disagreement requires orderStable=false and final HOLD. A score below 50 cannot
APPLY. Keep runtimeLineageVerdict=HOLD as an output classification outside the
canonical packet schema.

GoalContract
- goalId
- rewrittenUserIntent
- desiredOutcome
- measurableSuccess
- nonGoals
- authorizedMutationSurface
- prohibitedSurface
- evidenceBaseline
- assumptions
- constraints
- verificationOwner
- verificationCommands
- rollback
- stopConditions
- timeBudgetMinutes
- goalScore
- verdict
- evidence_needed

SourceDirective
- directiveId
- sourceOwner: notebook-local | desktop
- provenRoot
- provenBranch
- activeSourceSets
- targetFiles
- callPathOrBoundary
- beforeBehavior
- afterBehavior
- excludedFilesAndMirrors
- publicApiChange: forbidden | explicitly-approved
- secretMutation: forbidden
- redTest
- greenTest
- exactVerificationCommands
- expectedEvidence
- failureClassifications
- rollback
- patchdropContract
- desktopFinalProof=evidence_needed

The triad envelope is 120 seconds. If the envelope expires or a role fails validation,
emit only TriadExecutionStatus with the observed role provenance and failureClass.
```

- [ ] **Step 5: Register the prompt in the manifest**

Insert next to `demo1_three_node_smb_codex`:

```yaml
  - id: demo1_notebook_desktop_goal_handoff
    system: agents/demo1_notebook_desktop_goal_handoff/system_ko.md
    traits: []
    merge:
      order: [system]
      conflict: project_overrides_global
    output:
      path: out/demo1_notebook_desktop_goal_handoff.prompt
      encoding: utf-8
```

- [ ] **Step 6: Run tests and build the new prompt**

Run:

```powershell
python -B -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py -v
python -B -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_notebook_desktop_goal_handoff
```

Expected: all current handoff tests pass and the builder reports:

```text
Wrote agent-prompts\out\demo1_notebook_desktop_goal_handoff.prompt
```

- [ ] **Step 7: Review checkpoint**

Compare the new system prompt to its generated output byte-for-byte after newline normalization. Confirm no existing role prompt was edited. Do not commit.

---

### Task 3: Align the Existing Three-Node SMB Prompt

**Files:**
- Modify test: `scripts/test_notebook_desktop_goal_handoff_prompt.py`
- Modify prompt: `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md:18-114,235-304,445-488`
- Generate: `agent-prompts/out/demo1_three_node_smb_codex.prompt`

**Interfaces:**
- Consumes: the same three-field probe output and existing repository guard decision.
- Produces: `SMB_ACCESS`, `YDRIVE_SMB_GUARDED_DIRECT`, `LOCAL_PRODUCER`, or `HOLD`; never emits the historical mode or a raw network root.
- Invariant: matching identity is necessary but not sufficient for mutation.

- [ ] **Step 1: Add failing three-node policy tests**

Append to `HandoffPromptTests`:

```python
    def test_three_node_prompt_emits_only_current_ydrive_policy(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        self.assertIn("canonicalWorkspace=Y:\\", text)
        self.assertIn("YDRIVE_SMB_GUARDED_DIRECT", text)
        self.assertIn("sourceWriteRoot=Y:\\", text)
        self.assertIn("sourceWriteRoot=null", text)
        self.assertIn("authorizedMutation=false", text)
        self.assertNotIn("MACSRC_SMB_DIRECT", text)
        self.assertNotRegex(text, re.compile(r"\\\\[A-Za-z0-9._-]+\\"))
        self.assertNotIn("가장 오래된 pending patch부터 처리", text)
        self.assertIn("manifest-pinned cumulative v3", text)

    def test_three_node_prompt_keeps_write_gates_and_desktop_proof(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        for token in (
            "shared lease",
            "preimage",
            "index-lock",
            "focused verification",
            "postimage",
            "desktopFinalProof=evidence_needed",
        ):
            self.assertIn(token, text)

    def test_three_node_rejects_stale_network_reporting_and_keeps_authority(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        for stale in ("nas_path_used", "nas_connected", "nas-path-confusion",
                      "nas-write-permission", "notebook-offline-bundle-missing"):
            self.assertNotIn(stale, text)
        self.assertIn("Desktop exclusively owns its local final-verification checkout.", text)
        self.assertIn("YDRIVE_SMB_GUARDED_DIRECT is the sole Notebook canonical Y-drive write exception after every gate passes.", text)
        self.assertIn("PatchDrop-only producer handoff applies to LOCAL_PRODUCER and Mac mini, not YDRIVE_SMB_GUARDED_DIRECT.", text)
```

- [ ] **Step 2: Run the targeted tests and verify RED**

Run:

```powershell
python -B -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py -v
```

Expected: the new handoff package tests pass; three-node tests fail on historical mode, missing current mode, raw network paths, and FIFO patch selection.

- [ ] **Step 3: Replace the Notebook mode contract**

Replace the current `## 0.1 Notebook SMB mode contract` and its mode table with:

```text
canonicalWorkspace=Y:\
readMode=SMB_ACCESS
directMode=YDRIVE_SMB_GUARDED_DIRECT
producerMode=LOCAL_PRODUCER
sharedLeaseRequired=true
preimageCompareAndSwapRequired=true
desktopFinalProof=evidence_needed
```

Define behavior explicitly:

- `SMB_ACCESS`: safe reads/tools; `sourceWriteRoot=null`, `authorizedMutation=false`.
- `YDRIVE_SMB_GUARDED_DIRECT`: only explicit Notebook implementation plus identity match, declared targets, active boundary, repository guard, index-lock absence, shared lease, immediate preimage verification, focused verification, postimage hashes, rollback, and redaction.
- `LOCAL_PRODUCER`: only when the user selects isolation or direct mode is inapplicable; never an automatic fallback from identity failure.
- `HOLD`: no write root, no fallback, and one smallest decision-changing proof.

- [ ] **Step 4: Remove stale path and selection examples**

In the path, PatchDrop, public identity report, and mode-transition sections:

- replace Notebook source examples with `Y:\` only;
- keep the Desktop local canonical root as a Desktop-owned verification fact, never a Notebook write destination;
- remove literal network roots and commands that map an alternate drive;
- remove `nas_path_used`, `nas_connected`, connection-era classifiers, and path-valued backing evidence; public backing identity evidence is only `canonicalWorkspace`, `backingShareIdentityVerified`, and `backingShareIdentityReason`;
- replace FIFO/timestamp selection with `manifest-pinned cumulative v3` and one-active-bundle semantics;
- retain producer-local worktree and PatchDrop commands only as explicitly selected modes;
- state that Desktop exclusively owns its local final-verification checkout, `YDRIVE_SMB_GUARDED_DIRECT` is the sole gated Notebook canonical-write exception, and PatchDrop-only wording applies to Mac mini and `LOCAL_PRODUCER`;
- retain host-local Gradle caches and Desktop final proof.

- [ ] **Step 5: Run tests and rebuild the aligned prompt**

Run:

```powershell
python -B -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py -v
python -B -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_node_smb_codex
```

Expected: all handoff/three-node tests pass and the generated three-node prompt is rewritten from the manifest source.

- [ ] **Step 6: Review checkpoint**

Review the three-node diff alone. Confirm Mac mini producer isolation, Desktop final ownership, active source-set rules, leases, rollback, and cache separation remain intact. Do not commit.

---

### Task 4: Add the Short AGENTS Pointer and Verify Prompt Integration

**Files:**
- Modify test: `scripts/test_notebook_desktop_goal_handoff_prompt.py`
- Modify: `AGENTS.md:73-82`
- Read/verify: `agent-prompts/prompts.manifest.yaml`
- Read/verify generated prompts under `agent-prompts/out/`

**Interfaces:**
- Consumes: the new prompt ID and probe path from Tasks 1–3.
- Produces: one durable repository routing pointer; no copied long workflow.

- [ ] **Step 1: Add failing AGENTS pointer and baseline-preservation tests**

Append:

```python
    def test_agents_has_short_handoff_pointer_and_unchanged_baseline(self) -> None:
        text = AGENTS.read_text(encoding="utf-8")
        self.assertEqual(1, text.count("demo1_notebook_desktop_goal_handoff"))
        self.assertEqual(1, text.count("scripts\\verify_ydrive_backing_identity.ps1"))
        self.assertEqual(
            1,
            text.count("30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9"),
        )
```

- [ ] **Step 2: Run the pointer test and verify RED**

Run:

```powershell
python -B -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py -v
```

Expected: only the AGENTS pointer test fails.

- [ ] **Step 3: Add one short pointer**

Add after the existing `demo1_three_node_smb_codex` bullet:

```markdown
- Use `demo1_notebook_desktop_goal_handoff` for recurring Notebook read-only EvidenceSnapshot/three-role goal evaluation that must become a Desktop-owned GoalContract and SourceDirective. Run `scripts\verify_ydrive_backing_identity.ps1` before any Y-drive authority decision; keep its public output to the canonical workspace, verification boolean, and reason.
```

Do not change the existing baseline hash or duplicate the long prompt contract.

- [ ] **Step 4: Verify manifest uniqueness**

Run:

```powershell
$env:AGENT_ID = 'demo1_notebook_desktop_goal_handoff'
python -B -X utf8 -c "import os,yaml,pathlib,collections; m=yaml.safe_load(pathlib.Path('agent-prompts/prompts.manifest.yaml').read_text(encoding='utf-8')); ids=[a['id'] for a in m['agents']]; dup=[k for k,v in collections.Counter(ids).items() if v>1]; assert not dup,dup; assert os.environ['AGENT_ID'] in ids; print('YAML_OK agents=%d duplicate_ids=NONE' % len(ids))"
```

Expected: `YAML_OK` and `duplicate_ids=NONE`.

- [ ] **Step 5: Verify exact manifest merge output for both agents**

Run once with each ID:

```powershell
$env:AGENT_ID = 'demo1_notebook_desktop_goal_handoff'
@'
from pathlib import Path
import os, yaml
root = Path('agent-prompts')
manifest = yaml.safe_load((root / 'prompts.manifest.yaml').read_text(encoding='utf-8'))
agent = next(row for row in manifest['agents'] if row['id'] == os.environ['AGENT_ID'])
parts = []
for item in agent.get('merge', {}).get('order', ['trait', 'system']):
    if item == 'system':
        parts.append((root / agent['system']).read_text(encoding='utf-8').replace('\r\n', '\n'))
    elif item == 'trait':
        parts.extend((root / path).read_text(encoding='utf-8').replace('\r\n', '\n') for path in agent.get('traits', []))
expected = '\n\n'.join(parts)
observed = (root / agent['output']['path']).read_text(encoding=agent['output'].get('encoding', 'utf-8')).replace('\r\n', '\n')
print('manifest_merge_eq_out', expected == observed)
raise SystemExit(0 if expected == observed else 1)
'@ | python -B -X utf8 -
```

Repeat with `$env:AGENT_ID = 'demo1_three_node_smb_codex'`.

Expected twice: `manifest_merge_eq_out True`.

- [ ] **Step 6: Reserve the broad secret-pattern suite for the single Task 5 run**

Do not run the broad suite here. Task 5 runs it exactly once with `-B` and records
its actual exit, error count, and failure count. A nonzero repository baseline is
reported as nonzero and is never called PASS.

- [ ] **Step 7: Review checkpoint**

Confirm the AGENTS change is one bullet, the baseline count is still one, and generated files equal manifest composition. Do not commit.

---

### Task 5: Run the Complete Desktop Verification and Prepare Handoff Evidence

**Files:**
- Verify all files declared in Tasks 1–4.
- Do not create additional source, prompt, report, migration, or configuration files.

**Interfaces:**
- Consumes: green probe/prompt/policy tests and Desktop Git/worktree evidence.
- Produces: counts, hashes, reason codes, command results, rollback paths, `runtimeLineageVerdict=HOLD`, and `desktopFinalProof` based only on current Desktop output.

- [ ] **Step 1: Prove Desktop ownership and collision gates**

From the Desktop canonical checkout, run:

```powershell
git worktree list
git branch --show-current
git status --short
Test-Path .git\index.lock
Get-ChildItem .\__patch_drop__ -File -Filter '*.patch'
```

Expected: a Desktop-owned branch, no index lock, no overlapping dirty targets, and no active top-level patch. Otherwise classify the exact conflict and stop.

- [ ] **Step 2: Run every narrow contract and prompt test**

Run:

```powershell
$Expected = '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity.ps1 -CanonicalWorkspace 'Y:\' -ExpectedSha256 $Expected
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity_contract_tests.ps1
python -B -X utf8 scripts\ydrive_smb_workspace_policy_autograder.py --self-test
python -B -X utf8 scripts\test_ydrive_smb_workspace_policy_autograder.py -v
python -B -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py -v
python -B -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_notebook_desktop_goal_handoff
python -B -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_node_smb_codex
```

Expected: identity `match`, all targeted tests pass, and both prompts build.
Then run the broad suite exactly once and record its actual nonzero baseline
without calling it PASS:

```powershell
python -B -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

- [ ] **Step 3: Re-run manifest uniqueness and merge-equality checks**

Use the exact commands from Task 4. Expected: no duplicate IDs and equality `True` for both generated prompts.

- [ ] **Step 4: Run Desktop governance checks with isolated caches**

Run:

```powershell
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null
.\gradlew.bat sourceScoreReport checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
```

Expected: exit 0 for all three governance tasks. This does not prove application runtime behavior.

- [ ] **Step 5: Verify the mutation surface and record hashes**

Run:

```powershell
git diff --name-only
Get-FileHash -Algorithm SHA256 `
  scripts\verify_ydrive_backing_identity.ps1, `
  scripts\verify_ydrive_backing_identity_contract_tests.ps1, `
  scripts\test_notebook_desktop_goal_handoff_prompt.py, `
  agent-prompts\agents\demo1_notebook_desktop_goal_handoff\meta.yaml, `
  agent-prompts\agents\demo1_notebook_desktop_goal_handoff\system_ko.md, `
  agent-prompts\agents\demo1_three_node_smb_codex\system_ko.md, `
  agent-prompts\prompts.manifest.yaml, `
  AGENTS.md
```

Expected diff paths: only the files declared in this plan plus the two generated prompt outputs. Any application-source, DB, secret/config, archive, backup, or unrelated path is `undeclared-source-write` and stops completion.

- [ ] **Step 6: Exercise rollback on a temporary copy**

Run this proof against a temporary copy; it must not mutate the live checkout:

```powershell
$PreimageRoot = Join-Path ([IO.Path]::GetTempPath()) 'awx-notebook-desktop-handoff-preimage'
if (-not (Test-Path -LiteralPath $PreimageRoot -PathType Container)) {
    throw 'rollback-preimage-unproven'
}

$ExerciseRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-handoff-rollback-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $ExerciseRoot | Out-Null
$ResolvedExercise = [IO.Path]::GetFullPath($ExerciseRoot)
$ResolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
if (-not $ResolvedExercise.StartsWith($ResolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'rollback-exercise-outside-temp'
}
$RemovedRoot = Join-Path $ExerciseRoot '.removed'
New-Item -ItemType Directory -Path $RemovedRoot | Out-Null

function Move-ToRollbackQuarantine([string]$Target, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Target)) { return }
    $ResolvedTarget = [IO.Path]::GetFullPath($Target)
    if (-not $ResolvedTarget.StartsWith($ResolvedExercise, [StringComparison]::OrdinalIgnoreCase)) {
        throw "rollback-target-outside-exercise:$Label"
    }
    $Destination = Join-Path $RemovedRoot $Label
    if (Test-Path -LiteralPath $Destination) {
        throw "rollback-quarantine-collision:$Label"
    }
    Move-Item -LiteralPath $ResolvedTarget -Destination $Destination
}

Copy-Item -LiteralPath 'agent-prompts' -Destination (Join-Path $ExerciseRoot 'agent-prompts') -Recurse
Copy-Item -LiteralPath 'AGENTS.md' -Destination (Join-Path $ExerciseRoot 'AGENTS.md')
New-Item -ItemType Directory -Path (Join-Path $ExerciseRoot 'scripts') | Out-Null

$NewScripts = @(
    'scripts\verify_ydrive_backing_identity.ps1',
    'scripts\verify_ydrive_backing_identity_contract_tests.ps1',
    'scripts\test_notebook_desktop_goal_handoff_prompt.py'
)
foreach ($RelativePath in $NewScripts) {
    Copy-Item -LiteralPath $RelativePath -Destination (Join-Path $ExerciseRoot $RelativePath)
}

$RestoredFiles = @(
    'AGENTS.md',
    'agent-prompts\prompts.manifest.yaml',
    'agent-prompts\agents\demo1_three_node_smb_codex\system_ko.md'
)
foreach ($RelativePath in $RestoredFiles) {
    Copy-Item -LiteralPath (Join-Path $PreimageRoot $RelativePath) -Destination (Join-Path $ExerciseRoot $RelativePath) -Force
}

$GeneratedRelativePath = 'agent-prompts\out\demo1_three_node_smb_codex.prompt'
$GeneratedPreimage = Join-Path $PreimageRoot $GeneratedRelativePath
$GeneratedExercise = Join-Path $ExerciseRoot $GeneratedRelativePath
$GeneratedAbsentMarker = Join-Path $PreimageRoot 'absent\agent-prompts\out\demo1_three_node_smb_codex.prompt.absent'
if (Test-Path -LiteralPath $GeneratedPreimage -PathType Leaf) {
    Copy-Item -LiteralPath $GeneratedPreimage -Destination $GeneratedExercise -Force
    $GeneratedPreimageState = 'present'
} elseif (Test-Path -LiteralPath $GeneratedAbsentMarker -PathType Container) {
    Move-ToRollbackQuarantine $GeneratedExercise 'prior-generated-working-copy'
    $GeneratedPreimageState = 'absent'
} else {
    throw 'rollback-generated-preimage-unproven'
}

$NewPaths = @(
    'scripts\verify_ydrive_backing_identity.ps1',
    'scripts\verify_ydrive_backing_identity_contract_tests.ps1',
    'scripts\test_notebook_desktop_goal_handoff_prompt.py',
    'agent-prompts\agents\demo1_notebook_desktop_goal_handoff',
    'agent-prompts\out\demo1_notebook_desktop_goal_handoff.prompt'
)
for ($Index = 0; $Index -lt $NewPaths.Count; $Index++) {
    $RelativePath = $NewPaths[$Index]
    $Target = Join-Path $ExerciseRoot $RelativePath
    Move-ToRollbackQuarantine $Target ("new-path-{0:D2}" -f $Index)
}

$env:AWX_ROLLBACK_EXERCISE_ROOT = $ExerciseRoot
@'
import os
from pathlib import Path
import yaml

root = Path(os.environ["AWX_ROLLBACK_EXERCISE_ROOT"])
manifest = yaml.safe_load((root / "agent-prompts/prompts.manifest.yaml").read_text(encoding="utf-8"))
ids = [row["id"] for row in manifest["agents"]]
assert ids.count("demo1_three_node_smb_codex") == 1
assert "demo1_notebook_desktop_goal_handoff" not in ids
assert "demo1_notebook_desktop_goal_handoff" not in (root / "AGENTS.md").read_text(encoding="utf-8")
print("PASS rollback_manifest_contract failures=0")
'@ | python -X utf8 -

python -X utf8 (Join-Path $ExerciseRoot 'agent-prompts\build.py') `
    --manifest (Join-Path $ExerciseRoot 'agent-prompts\prompts.manifest.yaml') `
    --agent demo1_three_node_smb_codex

foreach ($RelativePath in $RestoredFiles) {
    $ExpectedHash = (Get-FileHash -Algorithm SHA256 (Join-Path $PreimageRoot $RelativePath)).Hash
    $ObservedHash = (Get-FileHash -Algorithm SHA256 (Join-Path $ExerciseRoot $RelativePath)).Hash
    if ($ExpectedHash -ne $ObservedHash) {
        throw "rollback-preimage-mismatch:$RelativePath"
    }
}

if ($GeneratedPreimageState -eq 'present') {
    $ExpectedGeneratedHash = (Get-FileHash -Algorithm SHA256 $GeneratedPreimage).Hash
    $ObservedGeneratedHash = (Get-FileHash -Algorithm SHA256 $GeneratedExercise).Hash
    if ($ExpectedGeneratedHash -ne $ObservedGeneratedHash) {
        throw 'rollback-generated-preimage-mismatch'
    }
} else {
    if (-not (Test-Path -LiteralPath $GeneratedExercise -PathType Leaf)) {
        throw 'rollback-prior-prompt-rebuild-missing'
    }
    Move-ToRollbackQuarantine $GeneratedExercise 'prior-generated-rebuild-proof'
    if (Test-Path -LiteralPath $GeneratedExercise) {
        throw 'rollback-generated-absence-not-restored'
    }
}

$EvidenceRoot = Join-Path $ResolvedTemp ('awx-handoff-rollback-evidence-' + [Guid]::NewGuid().ToString('N'))
$ResolvedEvidence = [IO.Path]::GetFullPath($EvidenceRoot)
if (-not $ResolvedEvidence.StartsWith($ResolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'rollback-evidence-outside-temp'
}
Move-Item -LiteralPath $ResolvedExercise -Destination $ResolvedEvidence
Remove-Item Env:AWX_ROLLBACK_EXERCISE_ROOT
[pscustomobject]@{
    rollbackEvidencePreserved = $true
    rollbackEvidenceFileCount = @(Get-ChildItem -LiteralPath $ResolvedEvidence -Recurse -File).Count
    liveCheckoutMutated = $false
}
```

Expected: `PASS rollback_manifest_contract failures=0`, a successful rebuild of the prior three-node prompt, three matching required-file hashes, exact restoration of a present generated preimage or quarantine back to its recorded absent state, and `rollbackEvidencePreserved=true` with no live-checkout mutation. Keep both the preimage directory and rollback-evidence directory until the user accepts the implementation; their later removal is a separate cleanup decision.

- [ ] **Step 7: Prepare the final evidence report**

Report:

```text
canonicalWorkspace=Y:\
backingShareIdentityVerified=true
backingShareIdentityReason=match
probeContractFailures=0
promptContractFailures=0
manifestDuplicateIds=0
manifestMergeMismatchCount=0
secretPatternErrors=<actual count>
secretPatternFailures=<actual count>
broadSecretSuiteStatus=NONZERO_BASELINE|PASS
applicationSourceWriteCount=0
supabaseProjectCallCount=0
databaseMutationCount=0
runtimeLineageVerdict=HOLD
desktopFinalProof=PASS|evidence_needed
```

Set `desktopFinalProof=PASS` only if all Desktop commands in this task have fresh successful output; otherwise retain `evidence_needed` with the exact failed or missing command.

- [ ] **Step 8: Final review checkpoint**

Do not commit, push, deploy, or send messages. Present the diff, hashes, verification results, and rollback evidence to the user for a separate publication decision.

---

## Plan Completion Criteria

- The live identity probe works on Windows PowerShell 5.1-compatible crypto APIs and never emits raw mapping/hash data.
- Its public JSON shape is exactly three fields for match, mismatch, and evidence-needed paths.
- The new handoff prompt is registered once, builds through the existing manifest, and contains the literal triad/contract/authority boundaries.
- The three-node prompt emits current Y-drive policy, contains no historical emitted mode, raw network root, or FIFO/timestamp patch selection.
- The short AGENTS pointer exists once and the repository baseline remains unchanged.
- Generated output equals manifest composition for both affected agents.
- The broad prompt secret-pattern suite's actual exit/error/failure counts are recorded; a nonzero baseline is not called PASS. Application-source writes, Supabase project calls, and DB mutations are all zero.
- Desktop governance commands pass or final proof remains `evidence_needed`; Notebook output never substitutes for Desktop proof.
