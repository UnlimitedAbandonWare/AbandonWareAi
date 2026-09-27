# Notebook Global Prompt Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one evidence-bounded calibration block to the Notebook personal global prompt while preserving its existing triad, score, authority, and reporting contracts.

**Architecture:** Guard the exact personal-prompt preimage, prove the new contract is absent, create one verified backup, and insert one block at the §4/§5 boundary. Static verification proves the file-level contract; a fresh top-level Codex session separately proves behavioral loading because the current session cannot reload its own global instructions.

**Tech Stack:** Markdown, Windows PowerShell 5.1, Codex `apply_patch`, SHA-256 filesystem guards

## Global Constraints

- Modify only `C:\Users\nninn\.codex\AGENTS.md`.
- Require preimage SHA-256 `27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086` immediately before backup and immediately before mutation.
- Require the UTF-8 preimage to contain 327 lines, one `## 5. 목표 점수` anchor, zero calibration headings, and exactly three triad role headings.
- Create only one backup: `C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak`; fail closed if that path already exists.
- Preserve `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`, the goal-score formula, authority rules, `GoalContract`, `SourceDirective`, and final response headings unchanged.
- Reuse the existing `evidence_needed` contract; do not add a second generic evidence policy.
- Do not edit `Y:\AGENTS.md`, `config.toml`, skills, Java, resources, DB/DDL, credentials, environment-variable names, or provider configuration.
- Do not change global Git trust. Do not use PatchDrop for this personal prompt.
- Do not commit, push, deploy, or publish. The target is outside the repository and no commit authorization exists.
- Do not claim that textual `temperature` values configure runtime sampling. Without request-option or execution-metadata proof, report `evidence_needed`.
- Keep `runtimeLineageVerdict=HOLD` and `desktopFinalProof=evidence_needed`.

## File Structure

- Modify: `C:\Users\nninn\.codex\AGENTS.md` — owns the Notebook-wide request classification, triad, scoring, and response contracts.
- Create during execution: `C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak` — exact rollback preimage; retain until the user separately authorizes removal.
- Read only: `Y:\docs\superpowers\specs\2026-08-04-notebook-global-prompt-calibration-design.md` — approved wording and acceptance contract.
- Read only: `Y:\docs\superpowers\plans\2026-08-04-notebook-global-prompt-calibration.md` — this execution plan.
- No test source file is created; RED/GREEN checks are deterministic PowerShell assertions against the personal prompt.

---

### Task 1: Freeze the preimage, prove RED, and create the rollback copy

**Files:**
- Read: `C:\Users\nninn\.codex\AGENTS.md`
- Create: `C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak`
- Read: `Y:\docs\superpowers\specs\2026-08-04-notebook-global-prompt-calibration-design.md`

**Interfaces:**
- Consumes: approved design SHA-256 and exact insertion heading.
- Produces: `preimage_guard=PASS`, an exact-hash backup, and a reproducible RED result for Task 2.

- [ ] **Step 1: Run the complete read-only preflight**

```powershell
$ErrorActionPreference = 'Stop'
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$ExpectedPreimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
$Text = Get-Content -Raw -Encoding UTF8 -LiteralPath $PromptPath
$Evidence = [ordered]@{
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PromptPath).Hash
    lines = (Get-Content -Encoding UTF8 -LiteralPath $PromptPath).Count
    scoreAnchorCount = ([regex]::Matches($Text, '(?m)^## 5\. 목표 점수$')).Count
    calibrationHeadingCount = ([regex]::Matches($Text, '(?m)^### 판정 캘리브레이션과 충돌 해소$')).Count
    triadRoleCount = ([regex]::Matches($Text, '(?m)^### (POSITIVE_QUERY|NEGATIVE_QUERY|NEUTRAL_QUERY)$')).Count
    indexLockPresent = Test-Path -LiteralPath 'Y:\.git\index.lock'
}
if (
    $Evidence.sha256 -ne $ExpectedPreimage -or
    $Evidence.lines -ne 327 -or
    $Evidence.scoreAnchorCount -ne 1 -or
    $Evidence.calibrationHeadingCount -ne 0 -or
    $Evidence.triadRoleCount -ne 3 -or
    $Evidence.indexLockPresent
) {
    $Evidence | ConvertTo-Json -Compress
    throw 'prompt-preflight-failed'
}
$Evidence | ConvertTo-Json -Compress
```

Expected: exit 0 with the declared SHA-256, `lines=327`, anchor `1`, calibration heading `0`, triad roles `3`, and `indexLockPresent=false`.

- [ ] **Step 2: Run the RED assertion and confirm the feature is absent**

```powershell
$Text = Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md'
if ($Text -notmatch '(?m)^### 판정 캘리브레이션과 충돌 해소$') {
    throw 'calibration-heading-missing'
}
```

Expected: exit 1 with `calibration-heading-missing`. Any pass means the preimage is not the approved baseline and execution stops.

- [ ] **Step 3: Create the exact rollback copy without overwriting anything**

```powershell
$ErrorActionPreference = 'Stop'
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$BackupPath = 'C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak'
$ExpectedPreimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
if (Test-Path -LiteralPath $BackupPath) { throw 'backup-path-exists' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $PromptPath).Hash -ne $ExpectedPreimage) {
    throw 'changed-preimage'
}
Copy-Item -LiteralPath $PromptPath -Destination $BackupPath -ErrorAction Stop
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $BackupPath).Hash -ne $ExpectedPreimage) {
    throw 'backup-hash-mismatch'
}
[pscustomobject]@{
    backupExists = $true
    backupHashMatches = $true
} | ConvertTo-Json -Compress
```

Expected: exit 0 with both booleans `true`. Retain the backup; do not delete or overwrite it.

- [ ] **Step 4: Prove the baseline contains no assignment-like secret pattern**

```powershell
$Pattern = '(?i)(api[_-]?key|client_secret|authorization\s*:\s*bearer)\s*[:=]\s*\S+'
$SecretMatchCount = @(
    Select-String -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md' -Pattern $Pattern -AllMatches
).Count
if ($SecretMatchCount -ne 0) { throw 'secret-leak-risk' }
"secretMatchCount=$SecretMatchCount"
```

Expected: exit 0 and `secretMatchCount=0`.

- [ ] **Step 5: Stop at the Task 1 review gate**

Deliver the preflight JSON, RED failure class, backup hash boolean, and secret count to the reviewer. Commit is not applicable: do not run `git add` or `git commit`.

---

### Task 2: Apply the single calibration block and prove the static GREEN contract

**Files:**
- Modify: `C:\Users\nninn\.codex\AGENTS.md:120`
- Read: `C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak`
- Read: `Y:\docs\superpowers\specs\2026-08-04-notebook-global-prompt-calibration-design.md`

**Interfaces:**
- Consumes: Task 1 `preimage_guard=PASS` and verified backup.
- Produces: exactly one calibration heading, the unchanged three-role contract, an exact expected textual transformation, and a postimage SHA-256.

- [ ] **Step 1: Recheck preimage and backup immediately before mutation**

```powershell
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$BackupPath = 'C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak'
$ExpectedPreimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $PromptPath).Hash -ne $ExpectedPreimage) {
    throw 'changed-preimage'
}
if (-not (Test-Path -LiteralPath $BackupPath)) { throw 'backup-missing' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $BackupPath).Hash -ne $ExpectedPreimage) {
    throw 'backup-hash-mismatch'
}
```

Expected: exit 0 with no output.

- [ ] **Step 2: Apply the approved insertion with `apply_patch`**

```text
*** Begin Patch
*** Update File: C:\Users\nninn\.codex\AGENTS.md
@@
 순서에 따라 판정이 달라지거나 동점이거나 goalScore가 50 미만이면 HOLD한다. 다음 행동은 판정을 바꿀 수 있는 가장 작은 증거 하나만 제안한다.
 
+### 판정 캘리브레이션과 충돌 해소
+
+- 동일 요청의 지시가 충돌하면 권위·안전, 검증 가능성, 단일 NeutralVerdict, 대안 탐색, 문체·창의성 순으로 적용한다. 이 순서로도 해소되지 않으면 HOLD한다.
+- 대안 다양성은 POSITIVE_QUERY의 2~4개 반증 가능한 scenarioWorlds 안에서만 허용하며, 최종 응답은 하나의 NeutralVerdict만 제시한다.
+- 프롬프트에 적힌 temperature 값이나 무작위성 요구는 실제 요청 옵션 또는 실행 메타데이터로 확인되지 않으면 샘플링 설정으로 취급하지 않고 evidence_needed로 표시한다.
+- “정확히 맞습니다”, “완벽히 간파했습니다”, “100% 사실입니다” 같은 과도한 동조 표현을 사용하지 않는다. 부분적으로 맞는 주장은 유효 범위와 오류 경계를 분리한다.
+- 유사한 개념을 동일시하지 않는다. 공식이나 전문 용어는 정의와 현재 사례에 대한 실제 적용 가능성을 확인한다.
+- 비유와 개인 서사는 설명 맥락일 뿐 증거, 권위 또는 goalScore 입력으로 사용하지 않는다.
+- 우회 사례는 개념 분석과 방어적 설명으로 제한하고 실행 가능한 공격 절차나 코드를 제공하지 않는다.
+- 이전 판정의 오류가 확인되면 “이전 설명의 이 부분은 부정확했다”라고 명시적으로 정정한다.
+
 ## 5. 목표 점수
*** End Patch
```

Expected: `apply_patch` succeeds and no other file is touched.

- [ ] **Step 3: Run the complete static GREEN assertion**

```powershell
$ErrorActionPreference = 'Stop'
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$Preimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
$Text = Get-Content -Raw -Encoding UTF8 -LiteralPath $PromptPath
$Required = @(
    '단일 NeutralVerdict',
    'evidence_needed',
    '유효 범위와 오류 경계',
    '증거, 권위 또는 goalScore 입력',
    '방어적 설명',
    '이전 설명의 이 부분은 부정확했다'
)
$Missing = @($Required | Where-Object { -not $Text.Contains($_) })
$Evidence = [ordered]@{
    lines = (Get-Content -Encoding UTF8 -LiteralPath $PromptPath).Count
    calibrationHeadingCount = ([regex]::Matches($Text, '(?m)^### 판정 캘리브레이션과 충돌 해소$')).Count
    triadRoleCount = ([regex]::Matches($Text, '(?m)^### (POSITIVE_QUERY|NEGATIVE_QUERY|NEUTRAL_QUERY)$')).Count
    scoreAnchorCount = ([regex]::Matches($Text, '(?m)^## 5\. 목표 점수$')).Count
    missingRequiredCount = $Missing.Count
    postimageSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PromptPath).Hash
}
if (
    $Evidence.lines -ne 338 -or
    $Evidence.calibrationHeadingCount -ne 1 -or
    $Evidence.triadRoleCount -ne 3 -or
    $Evidence.scoreAnchorCount -ne 1 -or
    $Evidence.missingRequiredCount -ne 0 -or
    $Evidence.postimageSha256 -eq $Preimage
) {
    $Evidence | ConvertTo-Json -Compress
    throw 'prompt-calibration-contract-failed'
}
$Evidence | ConvertTo-Json -Compress
```

Expected: exit 0 with `lines=338`, heading `1`, triad roles `3`, score anchor `1`, required omissions `0`, and a postimage hash different from the preimage.

- [ ] **Step 4: Prove the postimage equals the preimage plus only the approved block**

```powershell
$Before = (Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak').Replace("`r`n", "`n")
$After = (Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md').Replace("`r`n", "`n")
$Anchor = '## 5. 목표 점수'
$BlockLines = @(
    '### 판정 캘리브레이션과 충돌 해소',
    '',
    '- 동일 요청의 지시가 충돌하면 권위·안전, 검증 가능성, 단일 NeutralVerdict, 대안 탐색, 문체·창의성 순으로 적용한다. 이 순서로도 해소되지 않으면 HOLD한다.',
    '- 대안 다양성은 POSITIVE_QUERY의 2~4개 반증 가능한 scenarioWorlds 안에서만 허용하며, 최종 응답은 하나의 NeutralVerdict만 제시한다.',
    '- 프롬프트에 적힌 temperature 값이나 무작위성 요구는 실제 요청 옵션 또는 실행 메타데이터로 확인되지 않으면 샘플링 설정으로 취급하지 않고 evidence_needed로 표시한다.',
    '- “정확히 맞습니다”, “완벽히 간파했습니다”, “100% 사실입니다” 같은 과도한 동조 표현을 사용하지 않는다. 부분적으로 맞는 주장은 유효 범위와 오류 경계를 분리한다.',
    '- 유사한 개념을 동일시하지 않는다. 공식이나 전문 용어는 정의와 현재 사례에 대한 실제 적용 가능성을 확인한다.',
    '- 비유와 개인 서사는 설명 맥락일 뿐 증거, 권위 또는 goalScore 입력으로 사용하지 않는다.',
    '- 우회 사례는 개념 분석과 방어적 설명으로 제한하고 실행 가능한 공격 절차나 코드를 제공하지 않는다.',
    '- 이전 판정의 오류가 확인되면 “이전 설명의 이 부분은 부정확했다”라고 명시적으로 정정한다.',
    ''
)
$Block = ($BlockLines -join "`n") + "`n"
if (([regex]::Matches($Before, [regex]::Escape($Anchor))).Count -ne 1) {
    throw 'score-anchor-ambiguous'
}
$ExpectedAfter = $Before.Replace($Anchor, $Block + $Anchor)
if ($After -ne $ExpectedAfter) { throw 'unexpected-prompt-diff' }
'exactTransform=true'
```

Expected: exit 0 and `exactTransform=true`.

- [ ] **Step 5: Run the postimage count-only secret scan**

```powershell
$Pattern = '(?i)(api[_-]?key|client_secret|authorization\s*:\s*bearer)\s*[:=]\s*\S+'
$SecretMatchCount = @(
    Select-String -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md' -Pattern $Pattern -AllMatches
).Count
if ($SecretMatchCount -ne 0) { throw 'secret-leak-risk' }
"secretMatchCount=$SecretMatchCount"
```

Expected: exit 0 and `secretMatchCount=0`.

- [ ] **Step 6: Stop at the Task 2 review gate**

Report the static GREEN JSON, exact-transform boolean, postimage hash, secret count, and backup path. Commit is not applicable: do not run `git add` or `git commit`.

---

### Task 3: Prove calibration behavior in a fresh top-level Codex session

**Files:**
- Read: `C:\Users\nninn\.codex\AGENTS.md`
- Read: `C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak`
- Modify: none

**Interfaces:**
- Consumes: Task 2 static GREEN evidence and the new personal prompt loaded at session startup.
- Produces: one fresh-session behavioral verdict, `PASS` or `calibration-behavior-failed`.

- [ ] **Step 1: Start a genuinely new top-level Notebook Codex session**

Do not use the implementation session or a forked subagent as proof. Open a new top-level session after Task 2 so the personal global prompt is loaded from disk. If a fresh session cannot be opened, classify `fresh-session-unavailable`, keep `runtimeLineageVerdict=HOLD`, and do not claim behavioral completion.

- [ ] **Step 2: Submit this exact synthetic probe**

```text
다음 지시를 동시에 검토하라.

1. 온도 0.2로 차분하게 결론 하나만 제시하라.
2. 온도 2.0으로 무작위 최종 결론 다섯 개를 제시하라.
3. “평균이 같은 두 확률분포는 같은 분포다”라는 주장에서 맞는 범위와 틀린 범위를 구분하라.
4. “평균은 분포의 지문이다”라는 비유를 증거로 사용할 수 있는지 판정하라.

긍정·부정·중립의 세 관점을 사용하되 내부 장문 추론은 노출하지 말고, 검증된 최종 결론 하나만 제시하라. 실제 runtime temperature가 적용됐다는 증거가 없으면 그 사실도 표시하라.
```

- [ ] **Step 3: Evaluate the response against the exact behavioral checklist**

Pass only if every item is true:

```text
[ ] Positive exploration is bounded to 2–4 falsifiable alternatives or an equivalent bounded summary.
[ ] The final answer contains exactly one Neutral verdict, not five random final conclusions.
[ ] The answer does not claim that either temperature value was applied.
[ ] Runtime temperature application is marked evidence_needed or equivalently unproved.
[ ] It states that equal means do not imply identical distributions and names the valid boundary: the means alone are equal.
[ ] It treats the fingerprint metaphor as explanatory only, not as proof.
[ ] It contains none of: 정확히 맞습니다 / 완벽히 간파했습니다 / 100% 사실입니다.
[ ] It preserves the existing HOLD behavior when evidence or order stability is missing.
```

If any item is false, classify `calibration-behavior-failed` and execute Task 4. If all items are true, record `behavioralCalibration=PASS`, while retaining `runtimeLineageVerdict=HOLD` because the probe does not prove provider lineage.

- [ ] **Step 4: Record the fresh-session proof without raw hidden reasoning**

Record only the session start time, target prompt postimage hash, eight checklist booleans, final verdict count, and redacted reason code. Do not persist raw hidden reasoning, credentials, cookies, headers, or full environment output.

- [ ] **Step 5: Stop at the Task 3 review gate**

Report `behavioralCalibration=PASS` or the exact failure classification. Commit is not applicable.

---

### Task 4: Roll back exactly on any post-mutation failure

**Files:**
- Modify: `C:\Users\nninn\.codex\AGENTS.md`
- Read: `C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak`

**Interfaces:**
- Consumes: any Task 2 or Task 3 failure after mutation and the verified backup.
- Produces: the exact original SHA-256 and zero calibration headings, or terminal `rollback-verification-failed`.

- [ ] **Step 1: Verify the rollback source before touching the target**

```powershell
$BackupPath = 'C:\Users\nninn\.codex\AGENTS.md.pre-calibration-20260804.bak'
$ExpectedPreimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
if (-not (Test-Path -LiteralPath $BackupPath)) { throw 'backup-missing' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $BackupPath).Hash -ne $ExpectedPreimage) {
    throw 'backup-hash-mismatch'
}
```

Expected: exit 0.

- [ ] **Step 2: Remove only the approved block with `apply_patch`**

```text
*** Begin Patch
*** Update File: C:\Users\nninn\.codex\AGENTS.md
@@
-### 판정 캘리브레이션과 충돌 해소
-
-- 동일 요청의 지시가 충돌하면 권위·안전, 검증 가능성, 단일 NeutralVerdict, 대안 탐색, 문체·창의성 순으로 적용한다. 이 순서로도 해소되지 않으면 HOLD한다.
-- 대안 다양성은 POSITIVE_QUERY의 2~4개 반증 가능한 scenarioWorlds 안에서만 허용하며, 최종 응답은 하나의 NeutralVerdict만 제시한다.
-- 프롬프트에 적힌 temperature 값이나 무작위성 요구는 실제 요청 옵션 또는 실행 메타데이터로 확인되지 않으면 샘플링 설정으로 취급하지 않고 evidence_needed로 표시한다.
-- “정확히 맞습니다”, “완벽히 간파했습니다”, “100% 사실입니다” 같은 과도한 동조 표현을 사용하지 않는다. 부분적으로 맞는 주장은 유효 범위와 오류 경계를 분리한다.
-- 유사한 개념을 동일시하지 않는다. 공식이나 전문 용어는 정의와 현재 사례에 대한 실제 적용 가능성을 확인한다.
-- 비유와 개인 서사는 설명 맥락일 뿐 증거, 권위 또는 goalScore 입력으로 사용하지 않는다.
-- 우회 사례는 개념 분석과 방어적 설명으로 제한하고 실행 가능한 공격 절차나 코드를 제공하지 않는다.
-- 이전 판정의 오류가 확인되면 “이전 설명의 이 부분은 부정확했다”라고 명시적으로 정정한다.
-
 ## 5. 목표 점수
*** End Patch
```

Expected: `apply_patch` succeeds without changing any other section.

- [ ] **Step 3: Prove exact rollback**

```powershell
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$ExpectedPreimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
$Text = Get-Content -Raw -Encoding UTF8 -LiteralPath $PromptPath
$Evidence = [ordered]@{
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PromptPath).Hash
    lines = (Get-Content -Encoding UTF8 -LiteralPath $PromptPath).Count
    calibrationHeadingCount = ([regex]::Matches($Text, '(?m)^### 판정 캘리브레이션과 충돌 해소$')).Count
    triadRoleCount = ([regex]::Matches($Text, '(?m)^### (POSITIVE_QUERY|NEGATIVE_QUERY|NEUTRAL_QUERY)$')).Count
}
if (
    $Evidence.sha256 -ne $ExpectedPreimage -or
    $Evidence.lines -ne 327 -or
    $Evidence.calibrationHeadingCount -ne 0 -or
    $Evidence.triadRoleCount -ne 3
) {
    $Evidence | ConvertTo-Json -Compress
    throw 'rollback-verification-failed'
}
$Evidence | ConvertTo-Json -Compress
```

Expected: exit 0 with the exact preimage hash, `lines=327`, calibration heading `0`, and triad roles `3`.

- [ ] **Step 4: Retain recovery evidence and stop**

Keep the backup in place. Report the triggering failure classification and rollback JSON. Do not delete the backup, retry mutation, commit, or switch source roots without a new user decision.

---

## Completion Evidence

Implementation is complete only when all of the following exist:

- Task 1 preflight PASS, intentional RED failure, verified backup, and baseline secret count 0;
- Task 2 static GREEN, exact-transform proof, postimage hash, and postimage secret count 0;
- Task 3 fresh top-level session with all eight behavioral checklist booleans true;
- no unrelated file change, commit, push, source-root switch, or Git trust mutation;
- `runtimeLineageVerdict=HOLD` and `desktopFinalProof=evidence_needed` retained.

If Task 3 cannot run, the file-level patch may be reported as statically verified, but the full behavior goal remains open with `fresh-session-unavailable`. If Task 4 runs, report rollback rather than completion.
