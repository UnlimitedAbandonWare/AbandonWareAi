# Patch Potential Guard Existing-Asset Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the existing three-way source-edit preflight to the existing deterministic candidate grader, normalize the existing scanner as a read-only candidate producer, and retain immutable decision history without creating a new skill or mutation protocol.

**Architecture:** The existing source-edit hook continues to select `demo1-source-edit-three-way-preflight`. That skill freezes one evidence snapshot, produces exactly three logical packets, invokes `score_patch_potential_candidate.py`, and routes at most one eligible candidate into the unchanged source-owner guard. The scanner discovers candidates only; the existing mutation guard and postprocessor retain all write and terminal-proof authority.

**Tech Stack:** Markdown/YAML skill artifacts, PowerShell 5.1 contract tests, Python 3 standard library grader, SHA-256 sidecars, repository-local Superpowers process skills.

## Global Constraints

- Request class is `prompt_skill_tooling_only`; do not modify application source under `main/**`, `app/**`, or `src/test/**`.
- Work from `Y:\`; report only `canonicalWorkspace=Y:\`, backing identity boolean, and reason. Never print or persist the raw mapped backing path.
- Matching backing identity is not source-write authority.
- Do not change global Git trust or `safe.directory`.
- Do not commit, stage, push, deploy, mutate DB state, modify credentials, or send external messages without separate user authorization.
- Create no new skill, scorer, lease, compare-and-swap implementation, dispatcher, daemon, broker, retry loop, or rollback protocol.
- Keep `canonicalQueryCount=3`, `majorityVote=false`, one frozen evidence snapshot, and no fourth reviewer.
- Keep `goalScoreThreshold=85.0` as eligibility for hard-gate evaluation only; `autopatchEligible` is not source-write authorization.
- Preserve the existing source-owner guard and postprocessor unchanged.
- Store only relative paths, hashes, counts, durations, score components, allowlisted evidence IDs, and redacted reason codes.
- Keep `desktopFinalProof=evidence_needed` and `runtimeLineageVerdict=HOLD` until Desktop evidence proves otherwise.
- Stop on changed preimage, dirty overlap, index lock, secret hit, contract/live-tool mismatch, test regression, or any undeclared source write.
- Approved design: `docs/superpowers/specs/2026-07-31-patch-potential-guard-existing-asset-extension-design.md`.

## File Map

| File | Responsibility | Planned action |
| --- | --- | --- |
| `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md` | Trigger, bounded workflow, grader handoff, no-retry routing | Modify |
| `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md` | Live schemas, thresholds, audit history, owner delegation | Modify |
| `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1` | Structural and boundary RED/GREEN tests | Modify |
| `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md` | Read-only candidate discovery | Modify |
| `scripts/score_patch_potential_candidate.py` | Deterministic eligibility grader | Verify unchanged |
| `scripts/test_patch_potential_candidate.py` | Grader regression suite | Verify unchanged |
| `.codex/hooks/source_edit_triage.ps1` and `.codex/hooks.json` | Existing automatic trigger | Verify unchanged |
| `.agents/skills/demo1-macsrc-smb-direct-patch/**` | Existing source-write owner | Verify unchanged |
| `.agents/skills/demo1-macsrc-patch-postprocessor/**` | Existing terminal adjudicator | Verify unchanged |

Approved preimages:

```text
SKILL.md=1DBF09D038766DD91950060257566E17A706C2135C65914D2C75B3E1C5A4A7D9
preflight-contract.md=023B4D5B0185BDD7F37D3DCDA3825B3B125589322D30FE3A61876C08B5CF411D
preflight contract tests=3E3EB2020C894603E4906A0A487BA520BAE2AB455E92A7C69D1C9580590D0A1F
scanner prompt=705233DFC1D594FE6C972FDCCAA1670268648AC2C2F6A3E73BC77B0EDE62CD17
grader=B81C58048CCAA0FCEACA2722E780049E9E7FA31358CC214BAF30A63F1997DAC1
grader tests=B574423D1AE08F8C55EF791D78B2EE33FADBD708222C5FCC4FEE6A164B0DF9F4
```

---

### Task 1: Add the Existing-Grader and Audit Bridge with RED/GREEN

**Files:**

- Modify: `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1:103-168`
- Modify: `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md:10-56`
- Modify: `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md:1-101`
- Verify unchanged: `scripts/score_patch_potential_candidate.py`
- Verify unchanged: `.agents/skills/demo1-macsrc-smb-direct-patch/**`
- Verify unchanged: `.agents/skills/demo1-macsrc-patch-postprocessor/**`

**Interfaces:**

- Consumes: `awx.patch-potential.candidates.v1`, three ready packet references, `score_patch_potential_candidate.py --input --output --run-root`, and the existing source-owner guard modes.
- Produces: a documented preflight-to-grader bridge, immutable decision-run contract, exact threshold semantics, and existing-owner routing with no source-write authority.

- [ ] **Step 1: Reconfirm root identity, index lock, and every declared preimage**

Run from `Y:\`:

```powershell
$expectedIdentity = '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9'
$displayRoot = $null
try { $displayRoot = (Get-PSDrive -Name Y -ErrorAction Stop).DisplayRoot } catch {}
if ([string]::IsNullOrWhiteSpace($displayRoot)) {
    try { $displayRoot = (Get-SmbMapping -LocalPath 'Y:' -ErrorAction Stop | Select-Object -First 1).RemotePath } catch {}
}
$identityVerified = $false
$identityReason = 'evidence-needed'
if (-not [string]::IsNullOrWhiteSpace($displayRoot)) {
    $normalizedRoot = $displayRoot.Trim().TrimEnd([char]92).ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalizedRoot)) } finally { $sha.Dispose() }
    $actualIdentity = ([BitConverter]::ToString($digest)).Replace('-', '')
    $identityVerified = $actualIdentity -eq $expectedIdentity
    $identityReason = if ($identityVerified) { 'match' } else { 'mismatch' }
}
'canonicalWorkspace=Y:\'
"backingShareIdentityVerified=$($identityVerified.ToString().ToLowerInvariant())"
"backingShareIdentityReason=$identityReason"
"indexLockPresent=$((Test-Path -LiteralPath 'Y:\.git\index.lock').ToString().ToLowerInvariant())"
if (-not $identityVerified -or (Test-Path -LiteralPath 'Y:\.git\index.lock')) { exit 1 }

$expected = [ordered]@{
    '.agents\skills\demo1-source-edit-three-way-preflight\SKILL.md' = '1DBF09D038766DD91950060257566E17A706C2135C65914D2C75B3E1C5A4A7D9'
    '.agents\skills\demo1-source-edit-three-way-preflight\references\preflight-contract.md' = '023B4D5B0185BDD7F37D3DCDA3825B3B125589322D30FE3A61876C08B5CF411D'
    'scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1' = '3E3EB2020C894603E4906A0A487BA520BAE2AB455E92A7C69D1C9580590D0A1F'
    'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md' = '705233DFC1D594FE6C972FDCCAA1670268648AC2C2F6A3E73BC77B0EDE62CD17'
    'scripts\score_patch_potential_candidate.py' = 'B81C58048CCAA0FCEACA2722E780049E9E7FA31358CC214BAF30A63F1997DAC1'
    'scripts\test_patch_potential_candidate.py' = 'B574423D1AE08F8C55EF791D78B2EE33FADBD708222C5FCC4FEE6A164B0DF9F4'
}
$mismatchCount = 0
foreach ($entry in $expected.GetEnumerator()) {
    $actual = (Get-FileHash -LiteralPath (Join-Path 'Y:\' $entry.Key) -Algorithm SHA256).Hash
    if ($actual -cne $entry.Value) { $mismatchCount++ }
}
"declaredPreimageMismatchCount=$mismatchCount"
if ($mismatchCount -ne 0) { exit 1 }
```

Expected: identity `true/match`, index lock `false`, and `declaredPreimageMismatchCount=0`. Otherwise stop with `smb-root-identity-changed`, `index-lock-present`, or `changed-preimage` before any edit.

- [ ] **Step 2: Capture an application-source no-write manifest digest**

```powershell
$root = 'Y:\'
$rows = @(
    Get-ChildItem -LiteralPath 'Y:\main','Y:\app','Y:\src\test' -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/](build|build-[^\\/]+|\.gradle|node_modules)[\\/]' } |
        ForEach-Object {
            [ordered]@{
                relativePath = [IO.Path]::GetFullPath($_.FullName).Substring($root.Length).Replace('\','/')
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        } | Sort-Object relativePath
)
$json = $rows | ConvertTo-Json -Depth 3 -Compress
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)) } finally { $sha.Dispose() }
"applicationSourceFileCount=$($rows.Count)"
"applicationSourceManifestSha256=$(([BitConverter]::ToString($digest)).Replace('-', ''))"
```

Expected: one count and one digest only. Record both in this plan's SDD ledger;
do not create a manifest file or print individual source paths.

- [ ] **Step 3: Add bridge, audit, delegation, and no-duplicate-writer assertions first**

In `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`, immediately after the existing `$skillContract` definition, add:

```powershell
$graderFile = Join-Path $Root 'scripts\score_patch_potential_candidate.py'
$sourceOwnerGuard = Join-Path $Root '.agents\skills\demo1-macsrc-smb-direct-patch\scripts\macsrc_smb_patch_guard.ps1'
$postprocessor = Join-Path $Root '.agents\skills\demo1-macsrc-patch-postprocessor\scripts\new_postprocess_packet.ps1'

Test-Contract 'eligibility grader exists' (Test-Path -LiteralPath $graderFile -PathType Leaf)
Test-Contract 'existing source-owner guard exists' (Test-Path -LiteralPath $sourceOwnerGuard -PathType Leaf)
Test-Contract 'existing patch postprocessor exists' (Test-Path -LiteralPath $postprocessor -PathType Leaf)
```

After the existing skill/reference checks, add these exact structural contracts:

```powershell
if ((Test-Path -LiteralPath $skillFile) -and (Test-Path -LiteralPath $skillContract)) {
    $skillText = Get-Content -LiteralPath $skillFile -Raw -Encoding UTF8
    $contractText = Get-Content -LiteralPath $skillContract -Raw -Encoding UTF8

    foreach ($token in @(
        'score_patch_potential_candidate.py',
        'goalScoreThreshold=85.0',
        'canonicalQueryCount=3',
        'autopatchEligible is not source-write authorization',
        'no same-run retry',
        'existing-source-owner-guard'
    )) {
        Test-Contract "preflight bridge token $token" ($skillText.Contains($token))
    }

    foreach ($token in @(
        'awx.patch-potential.candidates.v1',
        'awx.patch-potential.positive.v1',
        'awx.patch-potential.negative.v1',
        'awx.patch-potential.neutral.v1',
        'awx.patch-potential.eligibility.v1',
        'AUTO_SINGLE_CANDIDATE',
        'python scripts\score_patch_potential_candidate.py --input "$RunRoot\input.json" --output "$RunRoot\autopatch.eligibility.json" --run-root "$RunRoot"',
        'exit 0=APPLY',
        'exit 1=HOLD',
        'exit 2|3=FAIL_CLOSED',
        'data/agent-handoff/patch-potential-guard/{runId}/',
        'run.intent.json',
        'evidence.snapshot.json',
        'positive.packet.json',
        'negative.packet.json',
        'neutral.decision.json',
        'candidate.ranking.json',
        'autopatch.eligibility.json',
        'mutation.reference.json',
        'verification.summary.json',
        'run.final.json',
        'run.final.ready'
    )) {
        Test-Contract "preflight contract token $token" ($contractText.Contains($token))
    }

    foreach ($token in @(
        '.agents/skills/demo1-macsrc-smb-direct-patch/scripts/macsrc_smb_patch_guard.ps1',
        '-Mode Prepare', '-Mode Verify', '-Mode Complete', '-Mode Abort',
        'authorized=true', 'ABORTED', 'ROLLBACK_REQUIRED',
        '.agents/skills/demo1-macsrc-patch-postprocessor/scripts/new_postprocess_packet.ps1'
    )) {
        Test-Contract "existing owner delegation token $token" ($contractText.Contains($token))
    }
}

if (Test-Path -LiteralPath $graderFile) {
    $graderText = Get-Content -LiteralPath $graderFile -Raw -Encoding UTF8
    foreach ($forbidden in @('apply_patch', 'git apply', 'Remove-Item', 'macsrc_smb_patch_guard.ps1', 'source-edit lease')) {
        Test-Contract "grader has no writer token $forbidden" (-not $graderText.Contains($forbidden))
    }
}
```

- [ ] **Step 4: Run the expanded test and verify structural RED**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `1`. Existing checks remain PASS; new failures name the absent grader CLI, schema, threshold-85, immutable-history, final-ready, and exact delegation markers. A parser error or failure in an old check is not an acceptable RED.

- [ ] **Step 5: Add the minimal positive routing recipe to the existing skill**

Insert this section after `## Fixed workflow` and before `## Invariants` in `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`:

````markdown
## Eligibility grader bridge

Set `goalScoreThreshold=85.0`. After the three packets are ready, set one
repo-relative `$RunRoot` beneath the immutable decision-history root and run:

```powershell
python scripts\score_patch_potential_candidate.py --input "$RunRoot\input.json" --output "$RunRoot\autopatch.eligibility.json" --run-root "$RunRoot"
```

Interpret the process exactly: `exit 0=APPLY`, `exit 1=HOLD`, and
`exit 2|3=FAIL_CLOSED`. Require one `selectedCandidateId` and no hard gates
before routing to `existing-source-owner-guard`.

`autopatchEligible is not source-write authorization`. The existing guard must
still return an immediately current `authorized=true` Verify result before the
declared external patch. After failed GREEN, require rollback and `-Mode Abort`;
there is `no same-run retry`. Terminal evidence is adjudicated by the existing
postprocessor.
````

Do not change the YAML description or broaden the trigger beyond explicit application-source mutation.

- [ ] **Step 6: Extend the existing reference with live schemas, thresholds, history, and owner delegation**

Append the following four sections to `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md`:

````markdown
## Eligibility grader bridge

The coordinator creates `awx.patch-potential.candidates.v1` with
`mutationMode=AUTO_SINGLE_CANDIDATE`, sealed evidence, candidates, and exactly
three ready packet references:

```text
awx.patch-potential.positive.v1
awx.patch-potential.negative.v1
awx.patch-potential.neutral.v1
```

All packets carry the same lower-case SHA-256 `evidenceSnapshotHash`,
`mutationAllowed=false`, and the same candidate-ID set. Candidate and reviewer
content may reference sealed evidence IDs; neither may create or rewrite the
sealed registry, hard gates, score threshold, ownership, or verdict.

Run from the proven repository root:

```powershell
python scripts\score_patch_potential_candidate.py --input "$RunRoot\input.json" --output "$RunRoot\autopatch.eligibility.json" --run-root "$RunRoot"
```

Interpret `exit 0=APPLY`, `exit 1=HOLD`, and `exit 2|3=FAIL_CLOSED`. The output
schema is `awx.patch-potential.eligibility.v1` with `verdict`, `failureClass`,
`selectedCandidateId`, `autopatchEligible`, `deletionEligible`,
`nextSingleAction`, `hardGates`, `hardGateCount`, `evaluatedCandidates`, and
`deterministicReplayHash`. Each evaluated candidate carries `candidateId`,
`computedGoalScore`, `scoreComponents`, `hardGates`, and `deletionEligible`.

## Score and authority

```text
goalScoreThreshold=85.0
score<50=THREE_WAY_HOLD_OR_REJECT
50<=score<85=SOURCE_DIRECTIVE_ONLY_AUTOMATIC_HOLD
score>=85=ELIGIBLE_FOR_NON_COMPENSABLE_HARD_GATES
```

A high score cannot compensate for missing sourceSet, call path, target set,
rollback, evidence binding, stable Neutral APPLY, or safety proof.
`autopatchEligible is not source-write authorization`; it permits entry into
the existing guard only. An inactive legacy-looking target remains
`LEGACY_INACTIVE_REPORTED` unless every deletion gate is proven.

## Immutable decision history

Decision evidence lives beneath:

```text
data/agent-handoff/patch-potential-guard/{runId}/
```

Required artifacts are:

```text
run.intent.json
evidence.snapshot.json
positive.packet.json
negative.packet.json
neutral.decision.json
candidate.ranking.json
autopatch.eligibility.json
mutation.reference.json
verification.summary.json
run.final.json
run.final.ready
```

Each JSON has a SHA-256 sidecar. Write to a same-directory temporary name,
flush, re-read, verify the digest, rename, and publish `run.final.ready` last.
Runs without the final marker are incomplete and excluded from aggregation.
Store relative paths, hashes, counts, durations, score components, allowlisted
evidence IDs, redacted reasons, and test summaries only. Never store raw prompts,
queries, responses, logs, provider payloads, credentials, authorization data,
cookies, private environment values, or a raw mapped backing path.

## Existing owner delegation

For an explicitly authorized Notebook source edit, delegate without a wrapper:

```text
.agents/skills/demo1-macsrc-smb-direct-patch/scripts/macsrc_smb_patch_guard.ps1
-Mode Prepare
-Mode Verify
-Mode Complete
-Mode Abort
```

Only the guard's immediately current Verify record with `authorized=true`
permits the declared patch. Successful rollback terminates as `ABORTED`; an
unproven rollback remains `ROLLBACK_REQUIRED`. There is no same-run retry.
After a terminal guard record, delegate adjudication to:

```text
.agents/skills/demo1-macsrc-patch-postprocessor/scripts/new_postprocess_packet.ps1
```
````

Keep the existing 50-point three-way minimum. The new 85-point threshold is a later, stricter automatic-eligibility gate.

- [ ] **Step 7: Run bridge GREEN and the unchanged grader suite**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
python scripts\test_patch_potential_candidate.py
python -m py_compile scripts\score_patch_potential_candidate.py scripts\test_patch_potential_candidate.py
```

Expected: the expanded preflight contract suite exits `0`; grader reports `Ran 31 tests` and `OK`; bytecode compilation exits `0` with no output. If only scanner assertions have not yet been added, no scanner-related result is expected in this task.

- [ ] **Step 8: Record a no-commit checkpoint**

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath `
  '.agents\skills\demo1-source-edit-three-way-preflight\SKILL.md',`
  '.agents\skills\demo1-source-edit-three-way-preflight\references\preflight-contract.md',`
  'scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1',`
  'scripts\score_patch_potential_candidate.py',`
  'scripts\test_patch_potential_candidate.py'
```

Expected: the first three hashes change; grader and grader-test hashes remain exactly equal to their approved preimages. Do not stage or commit.

---

### Task 2: Normalize the Existing Scanner as a Read-Only Producer

**Files:**

- Modify: `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1:103-175`
- Modify: `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md:1-197`

**Interfaces:**

- Consumes: a proven execution-owner `$Root`, active sourceSet evidence, filesystem scan observations, and allowlisted evidence IDs.
- Produces: `awx.patch-potential.scan.v1` candidate discovery with `mutationAllowed=false` and no score, verdict, owner, or deletion authority.

- [ ] **Step 1: Add scanner contract assertions before changing the prompt**

After the skill-path declarations in `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`, add:

```powershell
$scannerFile = Join-Path $Root 'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md'
Test-Contract 'patch scanner prompt exists' (Test-Path -LiteralPath $scannerFile -PathType Leaf)
if (Test-Path -LiteralPath $scannerFile) {
    $scannerText = Get-Content -LiteralPath $scannerFile -Raw -Encoding UTF8
    foreach ($token in @(
        'schemaVersion=awx.patch-potential.scan.v1',
        'mutationAllowed=false',
        'scoreIsMutationAuthority=false',
        'candidateKind=MODIFY|DELETE',
        'problemEvidenceIds',
        'redCommand',
        'greenCommands',
        'activeSourceSets'
    )) {
        Test-Contract "scanner contract token $token" ($scannerText.Contains($token))
    }
    Test-Contract 'scanner has no absolute Windows root' (-not [regex]::IsMatch($scannerText, '(?i)\b[A-Z]:\\'))
}
```

- [ ] **Step 2: Run scanner RED**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: exit `1`; the eight schema-field assertions and the hard-coded-root assertion fail, while every Task 1 assertion remains PASS.

- [ ] **Step 3: Replace the scanner's authority model and hard-coded roots**

Rewrite `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md` with these exact sections and rules:

````markdown
# demo1 오케스트레이션 패치 후보 스캐너

이 프롬프트는 실행 소유자가 증명한 저장소 루트에서 동작하는 읽기 전용 후보 생성기다.
소스, 테스트, 설정, 스킬, PatchDrop, DB, credential을 수정하지 않는다.

## 입력 경계

- 실행 소유자가 현재 세션의 `$Root`와 `activeSourceSets`를 파일/명령 증거로 제공해야 한다.
- 루트 또는 active sourceSet이 증명되지 않으면 `wrong-sourceset`으로 HOLD한다.
- root backend는 증명된 `main/java`, `main/resources`만 스캔한다.
- `:app`은 증명된 `app/src/main/java_clean`, `app/src/main/resources`만 스캔한다.
- archives, backups, generated output, `project/src/main/java`, `app/src/main/java`,
  `demo-1`, `lms-core`는 활성 증거가 없으면 제외한다.

## 읽기 전용 탐침

```powershell
Push-Location -LiteralPath $Root
try {
    .\gradlew.bat projects --no-daemon 2>&1 | Select-String 'Project'
    Select-String -Path 'main\java\ai\abandonware\nova\orch\aop\*.java' -Pattern 'proceed\(\)' -ErrorAction SilentlyContinue |
        Group-Object Path | Where-Object Count -gt 1 | Select-Object Name,Count
    Select-String -Path 'main\java\**\*.java' -Pattern 'catch.*Exception' -ErrorAction SilentlyContinue |
        Where-Object { $_ -notmatch 'TraceStore|disabledReason|failureClass|reason' } |
        Select-Object -First 20 Filename,LineNumber
    Select-String -Path 'main\java\com\example\lms\service\rag\**\*.java' -Pattern 'return null|return List\.of\(\)|return Map\.of\(\)' -ErrorAction SilentlyContinue |
        Where-Object { $_ -notmatch 'fallback|rescue' } |
        Select-Object -First 20 Filename,LineNumber
    Get-ChildItem -LiteralPath 'main\java' -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |
        Group-Object Name | Where-Object Count -gt 1 | Select-Object -First 15 Name,Count
    Select-String -Path 'main\java\**\*.java' -Pattern 'WebClient|RestTemplate|HttpClient' -ErrorAction SilentlyContinue |
        Where-Object { $_ -notmatch 'x-request-id|rid|sessionId|X-Session-Id' } |
        Select-Object -First 15 Filename,LineNumber
} finally {
    Pop-Location
}
```

정적 문자열 일치는 후보 관찰일 뿐 결함·인과·소유권 증명이 아니다. 각 후보에는
반증 가능한 `observedProblem`, `proposedCausalMechanism`, RED/GREEN 명령,
현재 증거 ID, 부족한 증거를 기록한다.

## 안정적인 후보 ID

정규화한 상대 target 경로들을 사전순으로 결합하고 `observedProblem`의 SHA-256을
붙인 UTF-8 문자열을 SHA-256으로 해시한다. `candidateId`는 `cand-`와 결과의 앞
16개 lower-case hex를 결합한다. timestamp, 열거 순서, 모델 선호를 사용하지 않는다.

## 출력 계약

```text
schemaVersion=awx.patch-potential.scan.v1
evidenceSnapshotHash
activeSourceSets
scannedFileCount
candidateCount
mutationAllowed=false
scoreIsMutationAuthority=false
candidates[]
  candidateId
  candidateKind=MODIFY|DELETE
  targetFiles[]
  problemEvidenceIds[]
  observedProblem
  proposedCausalMechanism
  redCommand
  greenCommands[]
  evidenceNeeded[]
```

## 하류 경계

스캐너는 GoalScore 구성요소, 임계값, APPLY/HOLD/REJECT 판정, source owner,
삭제 적격성, 실행 모드, 패치 지시서, mutation 권한을 만들지 않는다. 출력은 기존
`demo1-source-edit-three-way-preflight`의 EvidenceSnapshot 후보 입력일 뿐이다.
````

Do not retain the former 0-10 aggregate score, threshold `6`, Mac mini directive generation, report-history mutation, or any hard-coded Desktop path.

- [ ] **Step 4: Run scanner GREEN and prompt-pack regression**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
python scripts\test_three_perspective_chat_postprocess.py
```

Expected: preflight suite exits `0` with all Task 1 and Task 2 checks PASS; prompt-pack suite reports its current full count with `OK`. At the approved baseline that suite count is `14`.

- [ ] **Step 5: Verify the scanner is read-only and record a no-commit checkpoint**

```powershell
$scanner = Get-Content -LiteralPath 'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md' -Raw -Encoding UTF8
"absoluteWindowsRootCount=$([regex]::Matches($scanner, '(?i)\b[A-Z]:\\').Count)"
"legacySimpleThresholdCount=$([regex]::Matches($scanner, '합계\s*[≥>=]+\s*6').Count)"
"normalizedScanSchemaCount=$([regex]::Matches($scanner, 'schemaVersion=awx\.patch-potential\.scan\.v1').Count)"
Get-FileHash -LiteralPath 'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md' -Algorithm SHA256
```

Expected: `absoluteWindowsRootCount=0`, `legacySimpleThresholdCount=0`, `normalizedScanSchemaCount=1`, and one postimage hash. Do not stage or commit.

---

### Task 3: Validate the Edited Skill Family and Existing Owner Boundaries

**Files:**

- Verify: all four modified files
- Verify unchanged: `.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml`
- Verify unchanged: `.codex/hooks/source_edit_triage.ps1`
- Verify unchanged: `.codex/hooks.json`
- Verify unchanged: `.agents/skills/demo1-macsrc-smb-direct-patch/**`
- Verify unchanged: `.agents/skills/demo1-macsrc-patch-postprocessor/**`

**Interfaces:**

- Consumes: Task 1 and Task 2 GREEN artifacts.
- Produces: schema-valid skill metadata, family-validator evidence, unchanged trigger behavior, and current owner-suite counts.

- [ ] **Step 1: Run focused preflight and grader suites from a fresh process**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
python scripts\test_patch_potential_candidate.py
python -m py_compile scripts\score_patch_potential_candidate.py scripts\test_patch_potential_candidate.py
```

Expected: preflight suite exit `0`, grader `31/31 PASS`, and `py_compile` exit `0`.

- [ ] **Step 2: Validate the edited skill directly**

```powershell
$env:PYTHONUTF8 = '1'
python "$env:USERPROFILE\.codex\skills\.system\skill-creator\scripts\quick_validate.py" .\.agents\skills\demo1-source-edit-three-way-preflight
```

Expected: exit `0` and a valid-skill result. If frontmatter or link validation fails, fix only the reported line in the existing skill/reference; do not broaden the skill family.

- [ ] **Step 3: Run the repo-local skill-family summary validator**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 `
  -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 `
  -TrimCandidateCount 5 -SummaryJson
```

Expected: required-family validation remains successful, `secretPatternHits=0`, and the edited preflight has no frontmatter, metadata, or trigger-quality error. Optional sibling discovery warnings remain report-only unless the command itself returns a blocking required-family failure.

- [ ] **Step 4: Re-run unchanged automatic-hook behavior**

The preflight contract suite already exercises positive source-edit prompts, read-only non-triggers, bounded output, raw-prompt non-reflection, subdirectory routing, ambiguous roots, and depth exhaustion. Confirm those named checks remain PASS in the Step 1 output. Do not modify the hook or its config to obtain GREEN.

- [ ] **Step 5: Run existing source-owner and postprocessor suites**

Run sequentially to keep their temporary lease fixtures isolated:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_smb_direct_patch_contract_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_patch_postprocessor_contract_tests.ps1
```

Expected from the current intake baseline:

```text
source-owner guard: pass=47 fail=0
patch postprocessor: passed=90 failed=0
```

Require named PASS evidence for changed-preimage rejection, undeclared-write rejection, lease retention until rollback, byte-identical abort, failed-GREEN non-completion, and no chained mutation.

- [ ] **Step 6: Confirm all protected owner implementations retain their preimages**

```powershell
$protected = @(
  '.codex\hooks\source_edit_triage.ps1',
  '.codex\hooks.json',
  '.agents\skills\demo1-source-edit-three-way-preflight\agents\openai.yaml',
  'scripts\score_patch_potential_candidate.py',
  'scripts\test_patch_potential_candidate.py',
  '.agents\skills\demo1-macsrc-smb-direct-patch\SKILL.md',
  '.agents\skills\demo1-macsrc-patch-postprocessor\SKILL.md'
)
$protected | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash
    "protectedAsset=$($_);sha256=$hash"
}
```

Expected: current hashes are recorded. Grader and grader-test hashes must equal the approved preimages exactly. Any unintended protected-file change is `undeclared-source-write` for this tooling plan and stops execution.

---

### Task 4: Run Full Regression, Secret Scan, and No-Source-Write Proof

**Files:**

- Verify: the four declared modified files
- Verify unchanged: all application-source surfaces and protected assets
- Do not create: release, PatchDrop, commit, or deployment artifacts

**Interfaces:**

- Consumes: Task 1-3 GREEN evidence and the application-source count/digest recorded in this plan's SDD ledger.
- Produces: one compact final verification record with counts, hashes, failure classification, rollback statement, and Desktop evidence needs.

- [ ] **Step 1: Run all new and existing focused tooling tests**

```powershell
python scripts\test_patch_potential_candidate.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

Expected: grader `31/31 PASS`; expanded preflight suite exit `0` with no FAIL lines.

- [ ] **Step 2: Run the approved five-suite regression baseline sequentially**

```powershell
python scripts\test_source_health_scorecard.py
python scripts\test_three_way_long_tail_design_autograder.py
python scripts\test_three_way_long_tail_autograder.py
python scripts\test_three_perspective_chat_postprocess.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_goal_score_contract_tests.ps1
```

Expected approved baseline:

```text
source-health scorecard=56
design meta-autograder=29
artifact grader=59
three-perspective prompt pack=14
GoalScore contract=18
total=176
```

If a live suite reports a different total, report the observed count and failure class; never restate `176` as observed without matching output.

- [ ] **Step 3: Re-run deterministic replay directly**

```powershell
python scripts\test_patch_potential_candidate.py CliContractTests.test_cli_replay_is_byte_identical
```

Expected: `Ran 1 test` and `OK`, proving byte-identical eligibility output for equivalent ready packet sets.

- [ ] **Step 4: Run a count-only secret scan over exactly the four modified files**

```powershell
$declared = @(
  '.agents\skills\demo1-source-edit-three-way-preflight\SKILL.md',
  '.agents\skills\demo1-source-edit-three-way-preflight\references\preflight-contract.md',
  'scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1',
  'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md'
)
$secretHitCount = 0
foreach ($path in $declared) {
    $text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    $secretHitCount += [regex]::Matches($text, '(?i)authorization\s*:\s*bearer\s+[A-Za-z0-9._~+/-]{8,}').Count
    $secretHitCount += [regex]::Matches($text, '(?i)(api[_-]?key|client[_-]?secret|owner[_-]?token)\s*[=:]\s*[A-Za-z0-9._~+/-]{12,}').Count
    $secretHitCount += [regex]::Matches($text, 'sk-[A-Za-z0-9]{16,}').Count
}
"secretPatternHitCount=$secretHitCount"
if ($secretHitCount -ne 0) { exit 1 }
```

Expected: `secretPatternHitCount=0`. Do not print matches.

- [ ] **Step 5: Recompute and compare the application-source manifest digest**

```powershell
$root = 'Y:\'
$afterRows = @(
    Get-ChildItem -LiteralPath 'Y:\main','Y:\app','Y:\src\test' -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/](build|build-[^\\/]+|\.gradle|node_modules)[\\/]' } |
        ForEach-Object {
            [ordered]@{
                relativePath = [IO.Path]::GetFullPath($_.FullName).Substring($root.Length).Replace('\','/')
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        } | Sort-Object relativePath
)
$after = $afterRows | ConvertTo-Json -Depth 3 -Compress
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($after)) } finally { $sha.Dispose() }
$afterDigest = ([BitConverter]::ToString($digest)).Replace('-', '')
"applicationSourceFileCount=$($afterRows.Count)"
"applicationSourceManifestSha256=$afterDigest"
$unchanged = ($afterRows.Count -eq $Task1ApplicationSourceFileCount) -and ($afterDigest -ceq $Task1ApplicationSourceManifestSha256)
"applicationSourceManifestUnchanged=$($unchanged.ToString().ToLowerInvariant())"
"realApplicationSourceMutationCount=$(if ($unchanged) { 0 } else { 1 })"
if (-not $unchanged) { exit 1 }
```

Before running, set `$Task1ApplicationSourceFileCount` and
`$Task1ApplicationSourceManifestSha256` to the exact values in this plan's SDD
ledger. Expected: `applicationSourceManifestUnchanged=true` and
`realApplicationSourceMutationCount=0`.

- [ ] **Step 6: Record final postimage hashes and unchanged grader hashes**

```powershell
$finalFiles = @(
  '.agents\skills\demo1-source-edit-three-way-preflight\SKILL.md',
  '.agents\skills\demo1-source-edit-three-way-preflight\references\preflight-contract.md',
  'scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1',
  'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md',
  'scripts\score_patch_potential_candidate.py',
  'scripts\test_patch_potential_candidate.py'
)
$finalFiles | ForEach-Object {
    "asset=$($_);sha256=$((Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash)"
}
```

Expected: four new postimage hashes and the exact unchanged grader hashes from the File Map.

- [ ] **Step 7: Perform the plan/spec coverage audit**

Confirm every item below has current command or file evidence:

```text
canonicalQueryCount=3
goalScoreThreshold=85.0
candidateCountMutated<=1
hardGateBypassCount=0
deletionGatePresent=true
sameRunRetryAllowed=false
existingMutationGuardDelegation=true
existingPostprocessorDelegation=true
immutableReadyLastHistory=true
scannerMutationAllowed=false
scannerScoreIsMutationAuthority=false
secretPatternHitCount=0
realApplicationSourceMutationCount=0
graderTests=31/31
sourceOwnerGuardTests=47/47
postprocessorTests=90/90
desktopFinalProof=evidence_needed
runtimeLineageVerdict=HOLD
```

Missing evidence is not PASS. Record the exact verification command and classify it as `evidence_needed`.

- [ ] **Step 8: Deliver the no-commit final report**

Use the repository-required order:

```text
## 요약
## Observation
## Patch
## Verification
## Risks & Next
```

Include the four before/after hashes, commands and observed counts, count-only secret result, no-source-write proof, rollback, `confidence`, one next action, `runtimeLineageVerdict=HOLD`, and `desktopFinalProof=evidence_needed`. State explicitly that no commit, push, deploy, PatchDrop, DB mutation, credential change, or application-source mutation occurred.

Do not stage or commit.

## Rollback Procedure

If any GREEN or regression gate fails after a declared edit:

1. Stop without retrying another design variant.
2. Restore only the four declared files from their recorded preimages or an approved byte-identical backup.
3. Re-run the preflight contract, grader, source-owner guard, and postprocessor suites.
4. Recompute the application-source manifest and require it unchanged.
5. Report the exact failure class and retain `desktopFinalProof=evidence_needed`.

Do not remove immutable real decision history. No application-source manifest
file is created by this plan.
