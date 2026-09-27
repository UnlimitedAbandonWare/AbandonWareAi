/goal

# AWX Desktop Chat Restore Identity P0 후처리 소스수정 지시서

directiveId=AWX-CHAT-RESTORE-IDENTITY-P0-20260806  
approvalStatus=APPROVED  
executionOwner=새 Desktop Codex 세션  
canonicalRoot=C:\AbandonWare\demo-1\demo-1\src  
timeBudgetMax=9h  
commitPolicy=FORBIDDEN  
manifestRegistration=INTENTIONALLY_NONE  

## 0. 최종 목표

저장된 채팅 세션을 페이지 시작 시 복원하는 경로가 요청한 세션 ID와
다른 canonical detail.id를 반환받았을 때, 반환 세션의 ID, transcript,
control settings, mode badge, active run identity를 현재 세션에 채택하거나
렌더링하지 못하게 한다.

현재 live 파일에는 이 guard와 behavioral fixture가 이미 들어온 것으로
관찰됐다. 따라서 이 실행의 첫 번째 책임은 다시 패치하는 것이 아니라
현재 bytes와 계약을 재검증하는 것이다.

- guard와 테스트가 현재도 존재하고 전체 검증이 통과하면 소스를 수정하지
  말고 disposition=verified_no_patch_needed로 종료한다.
- source guard는 정확하지만 동등한 fixture만 없으면 fixture 한 파일만
  보강하고 disposition=test_contract_added_and_verified로 종료할 수 있다.
  이 경로는 source RED나 source patch를 주장하지 않는다.
- guard가 빠졌거나 회귀했고 기존 또는 새 RED가 정확히 재현될 때만 승인된
  두 파일에 최소 패치를 적용한다.
- 다른 실패나 소유권 충돌이면 HOLD 또는 evidence_needed로 종료한다.

9시간은 최대 예산이다. no-op이 증명되거나, 패치와 검증이 완료되거나,
외부 증거가 막히거나, 새 권한이 필요하면 즉시 조기 종료한다.

## 1. 시작 전에 반드시 읽을 파일

다음 순서로 UTF-8 전체 내용을 읽는다.

1. AGENTS.md
2. docs/superpowers/specs/2026-08-06-chat-restore-identity-postprocess-directive-design.md
3. docs/superpowers/plans/2026-08-06-chat-restore-identity-postprocess.md
4. .agents/skills/demo1-source-edit-three-way-preflight/SKILL.md
5. .agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md
6. main/resources/static/js/chat.js의 validateSessionDetail,
   selectSessionCandidate, hydrateRestoredSessionTranscript 전체
7. scripts/chat_ui_stream_contract_tests.js의 session hydration fetch fixture와
   normal restore, restoreIdentityCases 전체

설계와 계획의 현재 artifact anchors:

- design SHA-256:
  7EAAE52AAFA75057613F1DFA50D6BF1632652793A7B4EDECCFC79187A1A6A855
- plan SHA-256:
  0635516291DBEC79F7D919F4D97F1D4BF146D55B171E24E73B00B9F10ADF16B6

해시가 다르면 내용을 다시 읽고 의미가 승인안 A와 같은지 대조한다.
문서 해시만으로 소스 편집 권한을 추론하지 않는다.

## 2. Superpowers 실행 순서

이 지시서를 받은 새 세션은 다음 skill 순서를 사용한다.

1. superpowers:using-superpowers
2. superpowers:executing-plans
3. source/test 실패가 나오면 superpowers:systematic-debugging
4. 실제 소스 패치가 필요하면 superpowers:test-driven-development
5. 소스 변경 직전 demo1-source-edit-three-way-preflight
6. 완료를 주장하기 직전 superpowers:verification-before-completion

현재 작업은 사용자 승인된 설계와 계획을 다른 세션에서 실행하는 것이므로
새 brainstorming 승인 루프를 열지 않는다. 범위 또는 의미를 넓혀야 할
새 증거가 생길 때만 중단하고 별도 승인을 요청한다.

현재 target 두 파일이 untracked이므로 새 worktree에는 해당 bytes가
자동으로 따라오지 않는다. 이 지시서 실행 중 임의 worktree를 만들거나
과거 Git 객체에서 파일을 복원하지 않는다. canonical Desktop root에서
read-only reconciliation을 먼저 하고, 실제 패치가 필요한 경우에만
Desktop source-owner lease를 사용한다.

## 3. 권한과 절대 범위

### 변경 허용 대상

두 파일의 권한 조건은 서로 다르다.

1. scripts/chat_ui_stream_contract_tests.js
   - source guard boolean이 모두 true이고 동등 fixture만 없다는
     fixture_only_required matrix가 안정적이며, baseline Node가 green이고,
     exact fixture preimage와 ownership을 재확인한 경우 characterization
     fixture만 apply_patch로 추가할 수 있다.
   - source가 취약한 patch_required 경로에서는 wrong-ID RED를 만들기 위한
     최소 fixture만 추가할 수 있다.
2. main/resources/static/js/chat.js
   - stable three-way APPLY, source-owner lease, exact preimage, 취약 동작을
     가리키는 RED가 모두 충족된 경우에만 최소 수정할 수 있다.

### 읽기·검증 전용 대상

- AGENTS.md
- build.gradle.kts
- src/test/java/com/example/lms/web/ChatUiSendMessageContractTest.java
- main/resources/templates/chat-ui.html
- __patch_drop__의 inventory 및 source-edit lease 도구
- build output과 localhost UI

### 금지 대상

- 모든 HTML/CSS 수정
- Java controller, service, repository, Java test 수정
- Gradle, dependency, sourceSet 수정
- DB/DDL, Supabase, 외부 provider, credentials 수정
- prompt manifest 및 다른 prompt pack 수정
- session-list refresh coalescing 수정
- interactive selection 오류 알림 수정
- restore retry 또는 restoredSessionHydrated reset 의미 추가
- 이전 지시서, 사용자 파일, untracked 파일 삭제
- 브랜치 생성, git add, commit, push, pull request, stash
- git reset, git checkout, 강제 덮어쓰기
- 원문 응답, raw prompt, raw answer, cookie, auth header, 환경 덤프 저장

Browser는 마지막 정상 복원 happy-path 확인에만 쓴다. Computer Use와
Supabase는 이 작업의 의사결정에 필요하지 않으므로 활성화하지 않는다.

## 4. 승인 시점과 승인 후 live 증거

승인된 설계 스냅샷에서는 다음 취약 구현이 있었다.

~~~javascript
const restoredSessionId =
  normalizeSessionIdValue(detail?.sessionId ?? detail?.id) || sid;
rememberCurrentSessionId(restoredSessionId);
~~~

설계 승인 직후 공유 작업공간에서 target bytes가 바뀌었고 다음 구현이
관찰됐다.

~~~javascript
const validated = validateSessionDetail(sid, detail);
if (!validated) {
  setStatusRailValue(dom.traceStatus, "session restore unavailable");
  return false;
}
rememberCurrentSessionId(sid);
~~~

2026-08-06 23:22 KST 관찰값:

- branch=main
- HEAD=b6ec55d147f7ea4b5296e095b6f052811e1da42a
- chat.js SHA-256=
  1F6B275430A12832D848DFAB6C070C20D53092FDAB3AEDD8FA978F7C4DBD4C9E
- Node fixture SHA-256=
  FB7F2DDB8D8ADE58BB2ED5AC231E97F829D78FD0301C787A82F262C49D510D01
- 두 파일 Git 상태=untracked
- node contract=PASS
- stagedCount=0
- indexLock=false

이 변화의 작성자를 단정하지 않는다. 현재 해시와 코드가 같아도 ownership
authorization은 별도다. 반대로 해시가 달라도 현재 계약이 더 강하게
충족될 수 있으므로 과거 bytes로 되돌리지 않는다.

## 5. Phase A — read-only canonical preflight

PowerShell에서 실행한다.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location -LiteralPath $root

$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$auditReceiptOwner = 'codex-chat-restore-identity-20260806'
$auditReceiptSchema = 'awx.chat_restore.audit_receipt.v1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
$auditReceiptParent = [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      $tempRoot,$auditReceiptParent
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}

function Get-PorcelainZEntries {
  param([string]$RepositoryRoot)
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = 'git'
  $startInfo.Arguments =
    '-c core.quotepath=false status --porcelain=v1 -z --untracked-files=all'
  $startInfo.WorkingDirectory = $RepositoryRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  try {
    if (-not $process.Start()) { throw 'git-status-start-failed' }
    $rawStatus = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'git-status-failed' }
  } finally {
    $process.Dispose()
  }
  $records = @(
    $rawStatus.Split(
      [char[]]@([char]0),[StringSplitOptions]::RemoveEmptyEntries
    )
  )
  $entries = [System.Collections.Generic.List[object]]::new()
  for ($index = 0; $index -lt $records.Count; $index++) {
    $record = [string]$records[$index]
    if ($record.Length -lt 4) { throw 'git-status-record-invalid' }
    $status = $record.Substring(0,2)
    $paths = [System.Collections.Generic.List[string]]::new()
    $paths.Add($record.Substring(3).Replace('\','/'))
    if ($status.IndexOf('R') -ge 0 -or $status.IndexOf('C') -ge 0) {
      $index++
      if ($index -ge $records.Count) { throw 'git-status-rename-pair-missing' }
      $paths.Add(([string]$records[$index]).Replace('\','/'))
    }
    $entries.Add([pscustomobject]@{ status = $status; paths = $paths.ToArray() })
  }
  return $entries.ToArray()
}

if (Test-Path -LiteralPath $auditReceiptPath) {
  throw 'audit-receipt-collision'
}
$baselineHead = git rev-parse HEAD
$baselineGitEntries = @(Get-PorcelainZEntries -RepositoryRoot $root)
$baselineTargetHashes = @(
  $targets | ForEach-Object {
    [pscustomobject]@{
      path = $_
      hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
    }
  }
)
$auditReceiptJson = [ordered]@{
  schemaVersion = $auditReceiptSchema
  owner = $auditReceiptOwner
  canonicalRoot = $root
  baselineHead = $baselineHead
  baselineGitEntries = $baselineGitEntries
  targetHashes = $baselineTargetHashes
} | ConvertTo-Json -Depth 8 -Compress
$auditReceiptBytes = [Text.UTF8Encoding]::new($false).GetBytes(
  $auditReceiptJson
)
$auditReceiptStream = $null
try {
  $auditReceiptStream = [IO.File]::Open(
    $auditReceiptPath,
    [IO.FileMode]::CreateNew,
    [IO.FileAccess]::Write,
    [IO.FileShare]::None
  )
} catch [IO.IOException] {
  throw 'audit-receipt-collision'
}
$auditReceiptWriteFailed = $false
try {
  $auditReceiptStream.Write($auditReceiptBytes,0,$auditReceiptBytes.Length)
  $auditReceiptStream.Flush($true)
} catch {
  $auditReceiptWriteFailed = $true
} finally {
  $auditReceiptStream.Dispose()
}
if ($auditReceiptWriteFailed) {
  [IO.File]::Delete($auditReceiptPath)
  throw 'audit-receipt-write-failed'
}

$preflight = [ordered]@{
  canonicalRoot = (Get-Location).Path
  branch = (git branch --show-current)
  head = $baselineHead
  worktrees = @(git worktree list --porcelain)
  targetStatus = @(git status --short -- $targets)
  fullStatusEntryCount = $baselineGitEntries.Count
  stagedCount = @((git diff --cached --name-only)).Count
  indexLock = (Test-Path -LiteralPath '.git\index.lock')
  targetHashes = $baselineTargetHashes
  auditReceiptCreated = (Test-Path -LiteralPath $auditReceiptPath)
  pendingTopLevelPatches = @(
    Get-ChildItem -LiteralPath '__patch_drop__' -File -Filter '*-v3.patch' -ErrorAction SilentlyContinue |
      Select-Object -ExpandProperty Name
  )
}
$preflight | ConvertTo-Json -Depth 5
& '.\__patch_drop__\source_edit_session.ps1' -Action status -Role desktop -Root $root
~~~

exclusive audit receipt만 baselineHead, NUL-delimited structured Git entries,
preimage hash를 PowerShell 호출 사이에 전달한다. 덮어쓰기, repo 이동, public
raw path 출력 금지다. 모든 early HOLD에서는 exact temp parent/name/schema/
owner/canonicalRoot를 검증한 뒤 이 receipt 하나만 삭제한다.

판정:

- canonical root 불일치: HOLD root-mismatch
- .git/index.lock 존재: HOLD index-lock-conflict
- stagedCount가 0이 아님: HOLD staged-overlap
- active source lease 존재: HOLD source-lease-collision
- top-level PatchDrop와 Desktop source-owner 충돌: HOLD patch-drop-pending
- target ownership이 불명확한 채 mutation이 필요함: HOLD dirty-overlap

read-only no-op 판단은 mutation 권한을 요구하지 않는다. 실제 파일 쓰기
직전에만 stable three-way APPLY와 source-owner lease가 필요하다.

활성 owner도 다시 확인한다.

~~~powershell
rg -n 'main/resources|chatUiTest|src/chatUiTest' -- AGENTS.md build.gradle.kts
rg -n 'function validateSessionDetail|async function selectSessionCandidate|async function hydrateRestoredSessionTranscript' -- main/resources/static/js/chat.js
rg -n '__sessionHydrationDetail|restoreIdentityCases|detail-id-mismatch|mismatchedRestoreResult' -- scripts/chat_ui_stream_contract_tests.js
~~~

root main/resources가 active resource owner가 아니거나 chatUiTest가 사라졌으면
HOLD active-owner-changed로 종료한다.

## 6. Phase B — current contract/no-op probe

다음 정적 probe를 실행한다.

~~~powershell
$chatPath = 'main/resources/static/js/chat.js'
$fixturePath = 'scripts/chat_ui_stream_contract_tests.js'
$chat = Get-Content -LiteralPath $chatPath -Raw -Encoding UTF8
$fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}
try {
  $auditReceipt = Get-Content -LiteralPath $auditReceiptPath -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
} catch {
  throw 'audit-receipt-invalid'
}
if ($auditReceipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
    $auditReceipt.owner -cne 'codex-chat-restore-identity-20260806' -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$auditReceipt.canonicalRoot,$root
    )) {
  throw 'audit-receipt-owner-mismatch'
}
$preimageChatHash = [string]@(
  $auditReceipt.targetHashes | Where-Object path -CEQ $chatPath
)[0].hash
$preimageFixtureHash = [string]@(
  $auditReceipt.targetHashes | Where-Object path -CEQ $fixturePath
)[0].hash

$probe = [ordered]@{
  chatHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $chatPath).Hash
  fixtureHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $fixturePath).Hash
  usesSharedValidator =
    $chat.Contains('const validated = validateSessionDetail(sid, detail);')
  rejectsInvalidBeforeMutation =
    $chat.Contains('if (!validated) {') -and
    $chat.Contains('setStatusRailValue(dom.traceStatus, "session restore unavailable");')
  remembersRequestedSid =
    $chat.Contains('rememberCurrentSessionId(sid);')
  usesValidatedMessages =
    $chat.Contains('const messages = validated.messages;')
  appliesValidatedSettings =
    $chat.Contains('applyRestoredSessionSettings(validated);')
  restoresRequestedBadge =
    $chat.Contains('restoreSessionModeBadge(sid, validated);')
  resumesRequestedRun =
    $chat.Contains('resumeStoredRunIfNeeded(sid)')
  permissiveFallbackAbsent =
    -not $chat.Contains(
      'normalizeSessionIdValue(detail?.sessionId ?? detail?.id) || sid'
    )
  wrongIdBehaviorFixture =
    $fixture.Contains("label: 'detail-id-mismatch'") -and
    $fixture.Contains('mismatchedRestoreResult === false')
  noLeakFixture =
    $fixture.Contains('PRIVATE_CROSS_SESSION_TRANSCRIPT_SENTINEL_') -and
    $fixture.Contains('session restore unavailable')
}
$probe | ConvertTo-Json
if ([string]$probe.chatHash -cne $preimageChatHash -or
    [string]$probe.fixtureHash -cne $preimageFixtureHash) {
  throw 'changed-preimage'
}
~~~

아래 branch-specific matrix를 적용한다. 정적 probe만으로 성공을 선언하지
말고 behavioral fixture를 실행한다.

- no-op: 모든 source-guard boolean과 두 fixture boolean이 true이며 Node=0
- fixture-only: 모든 source-guard boolean=true, fixture boolean 중 하나
  이상=false, 변경 전 baseline Node=0
- source patch: source-guard boolean 중 하나 이상=false이고, 기존 fixture가
  wrong-ID adoption으로 실패하거나 fixture 추가 후 그 RED를 재현 가능
- HOLD: unrelated Node failure, mixed/ambiguous matrix, hash/ownership drift

fixture-only matrix는 exact fixture preimage와 ownership 재확인 뒤 테스트
파일에 characterization block만 apply_patch로 추가할 명시적 권한이다.
취약 source RED나 application-source 권한으로 승격하지 않는다.

~~~powershell
node scripts/chat_ui_stream_contract_tests.js
~~~

현재 기대 출력:

~~~text
[AWX][chat-ui] stream heartbeat contract OK
~~~

두 해시를 즉시 다시 계산한다.

~~~powershell
Get-FileHash -Algorithm SHA256 -LiteralPath $targets |
  Select-Object Path,Hash
~~~

분기:

### B1. no-op candidate

모든 boolean=true, Node exit=0, 전후 해시 동일이면
noPatchCandidate=true로 기록한다. 소스를 건드리지 않고 Phase E 검증
사다리로 이동한다.

### B2. fixture-only candidate

source guard boolean은 모두 true이고 동등한 wrong-ID fixture만 없으면
fixtureOnlyCandidate=true로 기록한다. Phase C를 건너뛰고 Phase D1에서
fixture characterization만 추가한다. application source mutation이 아니므로
source-edit three-way preflight와 source-owner lease를 사용하지 않는다.

### B3. source patch candidate

다음 중 하나면 Phase C로 이동한다.

- 기존 wrong-ID assertion이 취약 동작 때문에 실패
- hydrate가 validateSessionDetail보다 먼저 ownership 또는 UI를 변경
- returned ID 42가 current session, storage, transcript, settings, badge,
  resume target 중 하나로 채택됨

fixture가 없고 source도 취약하면 먼저 fixture를 추가해 RED를 재현한 뒤
source patch로 이동한다.

### B4. HOLD

다음이면 수정하지 않는다.

- Node의 다른 대규모 계약이 실패
- probe 중 target hash가 바뀜
- fixture route가 session 321 restore request를 더 이상 소유하지 않음
- target ownership 또는 현재 bytes가 계속 흔들림

HOLD 시 exact failing assertion과 nextSingleProof 하나만 보고한다.

## 7. Phase C — source-edit three-way preflight

Phase B3일 때만 수행한다. demo1-source-edit-three-way-preflight의 현재
계약을 그대로 사용한다.

### EvidenceSnapshot

- evidenceRowCountMax=20
- evidenceSummaryCharsMax=6000
- rawPromptStored=false
- rawResponseStored=false
- largeArtifactMode=path-plus-hash
- canonicalQueryCount=3
- processMode=single-agent-logical-roles
- preflightWallClockMaxSeconds=120

snapshot에는 target path, Git 상태, preimage hash, relevant function
booleans, Node failing assertion, source owner, 검증 명령만 넣는다.

### POSITIVE_QUERY

같은 evidenceSnapshotHash 위에서 2~4개 scenarioWorlds를 만든다. 최소
scenario set은 다음 의미를 포함한다.

- S1: returned detail.id mismatch가 cross-session adoption을 유발한다.
- S2: shared validator reuse가 최소 수정으로 mutation-before-validation을
  제거한다.
- S3: existing same-ID/found=false/concurrency behavior가 보존 가능하다.

각 scenario에 premise, causalMechanism, expectedObservation, evidenceNeeded,
falsifier, evidenceIds를 넣는다.

### NEGATIVE_QUERY

동일 snapshot과 정확히 같은 S1/S2/S3 ID를 사용한다. 다음 반증을
검토한다.

- current source가 이미 guard를 포함해 패치가 중복일 수 있음
- fixture failure가 restore identity가 아닌 다른 harness drift일 수 있음
- detail.id canonical authority가 현재 response schema와 다를 수 있음
- untracked target ownership 때문에 mutation 권한이 부족할 수 있음
- retry 또는 alias 의미를 넓히는 blast radius가 생길 수 있음

새 증거를 수집하지 않는다.

### NEUTRAL_QUERY

Positive-Negative와 Negative-Positive 순서로 각각 판정한다. 다음 고정
식을 사용하고 0~100으로 clamp한다.

~~~text
100 * (0.25*evidenceStrength + 0.20*causalStrength
+ 0.15*verificationFeasibility + 0.15*userValue + 0.10*reversibility
+ 0.10*costEfficiency + 0.05*timeFit - 0.20*blastRadius
- 0.15*ambiguity - 0.20*authorityOrSafetyExpansion)
~~~

다음을 모두 만족해야 APPLY다.

- forwardVerdict=APPLY
- reverseVerdict=APPLY
- verdict=APPLY
- forward/reverse decisiveEvidenceIds 동일
- orderStable=true
- goalScore가 50 이상
- verification available
- safety와 ownership 확장 없음
- nextWorkflow=existing-source-owner-guard

order 변화, scenario ID 불일치, missing verification, 120초 초과,
source-owner-unproven이면 HOLD한다. redaction 실패 또는 undeclared write면
REJECT한다. 다수결을 사용하거나 네 번째 reviewer를 만들지 않는다.

## 8. Phase D — 조건부 TDD와 최소 패치

Phase B3의 stable APPLY 뒤에만 source-owner lease를 획득한다. Phase B2
fixture-only 경로는 lease를 만들지 않는다.

~~~powershell
$leaseArgs = @{
  Action = 'begin'
  Role = 'desktop'
  Root = 'C:\AbandonWare\demo-1\demo-1\src'
  Topic = 'chat-restore-identity-postprocess'
  OwnerId = 'codex-chat-restore-identity-20260806'
  TtlMinutes = 180
}
& '.\__patch_drop__\source_edit_session.ps1' @leaseArgs
if ($LASTEXITCODE -ne 0) { throw 'source-owner-lease-not-acquired' }
~~~

apply_patch 직전에 target hashes를 Phase B 값과 비교한다. 다르면
HOLD changed-preimage로 전환하고 lease를 해제한다.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}
$auditReceipt = Get-Content -LiteralPath $auditReceiptPath -Raw -Encoding UTF8 |
  ConvertFrom-Json -ErrorAction Stop
if ($auditReceipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
    $auditReceipt.owner -cne 'codex-chat-restore-identity-20260806' -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$auditReceipt.canonicalRoot,$root
    )) {
  throw 'audit-receipt-owner-mismatch'
}
$currentHashes = @(
  $targets | ForEach-Object {
    [pscustomobject]@{
      path = $_
      hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
    }
  }
)
foreach ($currentHash in $currentHashes) {
  $expected = @(
    $auditReceipt.targetHashes | Where-Object path -CEQ $currentHash.path
  )
  if ($expected.Count -ne 1 -or
      [string]$expected[0].hash -cne [string]$currentHash.hash) {
    throw 'changed-preimage'
  }
}
$currentHashes
~~~

### D1. RED fixture

현재 harness는 restore override를 /api/chat/sessions/321에 연결하므로
requested ID 321, returned canonical ID 42를 사용한다. 설계의 41/42는
의미 예시이며 321/42가 현재 executable seam이다.

현재 파일에 restoreIdentityCases 블록이 이미 있으면 삭제, 축소, 복제하지
않는다. 다음 invariant가 모두 들어 있는지만 확인한다.

- hydrate result=false
- state.currentSessionId=321 유지
- chat.currentSessionId storage=321 유지
- active run identity 불변
- transcript에 returned sentinel 없음
- settings/control/composer 불변
- mode badge에 session 42 없음
- session 42 run resume call 없음
- trace status는 고정값 session restore unavailable
- raw error 또는 response content 노출 없음

equivalent block이 없을 때만 승인된 계획의 Task 2 Step 4 코드를
apply_patch로 추가한다.

RED 실행:

~~~powershell
node scripts/chat_ui_stream_contract_tests.js
~~~

Phase B3의 취약 소스라면 wrong-ID invariant에서 nonzero여야 한다.

Phase B2 fixture-only 경로에서는 source guard가 이미 있으므로 새
characterization fixture가 green이어야 한다. 그 fixture를 유지하고
disposition=test_contract_added_and_verified로 Phase E에 이동한다.
source RED나 source patch가 있었다고 주장하지 않는다.

pre-existing 동등 fixture가 뒤늦게 확인되면 새로 중복 추가한 block만
apply_patch로 제거하고 verified_no_patch_needed 후보로 전환한다. 기존
사용자 assertion은 제거하지 않는다.

### D2. minimal source diff

RED가 취약 동작으로 실패할 때만 hydrateRestoredSessionTranscript 안에서
다음 semantic diff를 apply_patch로 적용한다.

~~~diff
-    const restoredSessionId = normalizeSessionIdValue(detail?.sessionId ?? detail?.id) || sid;
-    rememberCurrentSessionId(restoredSessionId);
-    const messages = Array.isArray(detail?.messages) ? detail.messages : [];
+    const validated = validateSessionDetail(sid, detail);
+    if (!validated) {
+      setStatusRailValue(dom.traceStatus, "session restore unavailable");
+      return false;
+    }
+    rememberCurrentSessionId(sid);
+    const messages = validated.messages;
     if (!hasExistingMessages) {
       for (const message of messages) {
-        const role = String(message?.role || "").toLowerCase();
-        if (role !== "user" && role !== "assistant") continue;
-        appendMessage(role, message?.content || "");
+        appendMessage(message.role, message.content);
       }
     }
-    applyRestoredSessionSettings(detail);
-    restoreSessionModeBadge(restoredSessionId, detail);
-    void resumeStoredRunIfNeeded(restoredSessionId);
+    applyRestoredSessionSettings(validated);
+    restoreSessionModeBadge(sid, validated);
+    void resumeStoredRunIfNeeded(sid);
~~~

보존할 것:

- fetch 직후 generation/current-session/transcript guard
- response.json 직후 같은 guard
- found=false cleanup
- catch의 403/404 cleanup
- hasExistingMessages 동작
- 정상 id=321 응답의 messages/settings/badge/resume
- validateSessionDetail의 기존 shape filter
- canonical detail.id가 321이고 sessionId alias가 42인 경우 321만 사용

추가하지 말 것:

- 새 validator
- sessionId alias validator
- retry
- 새 status node
- controller 변경
- session list refresh flag

GREEN 실행:

~~~powershell
node scripts/chat_ui_stream_contract_tests.js
~~~

기대 출력:

~~~text
[AWX][chat-ui] stream heartbeat contract OK
~~~

실패하면 systematic-debugging으로 failing assertion과 causal seam만
분석한다. 두 파일 밖으로 범위를 넓히지 않는다.

## 9. Phase E — 검증 사다리

no-op, fixture-only, patched candidate 모두 이 검증을 수행한다.

각 PowerShell 호출은 self-contained다. 아래 한 block을 순서대로 실행하고
각 exit code를 즉시 판정한다. 실패 뒤 단계는 초기값 not_run으로 남는다.

~~~powershell
$env:AWX_AGENT_HOST = 'desktop-chat-restore'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-chat-restore'
$env:GRADLE_USER_HOME =
  Join-Path $env:USERPROFILE '.gradle-awx-desktop-chat-restore'
$projectCache =
  Join-Path $env:USERPROFILE '.awx-gradle-project-cache\desktop-chat-restore'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$projectCache |
  Out-Null

$verification = [ordered]@{
  nodeContract = 'not_run'
  focusedJUnit = 'not_run'
  chatUiTest = 'not_run'
  classes = 'not_run'
  appClasses = 'not_run'
  bootJar = 'not_run'
}
function Invoke-VerificationGate {
  param([string]$Name,[scriptblock]$Command)
  & $Command
  $exitCode = $LASTEXITCODE
  $verification[$Name] = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
  if ($exitCode -ne 0) {
    $verification | ConvertTo-Json
    throw "$Name-failed"
  }
}

Invoke-VerificationGate 'nodeContract' {
  node scripts/chat_ui_stream_contract_tests.js
}
Invoke-VerificationGate 'focusedJUnit' {
  .\gradlew.bat test --tests 'com.example.lms.web.ChatUiSendMessageContractTest' --no-daemon --project-cache-dir $projectCache
}
Invoke-VerificationGate 'chatUiTest' {
  .\gradlew.bat chatUiTest --no-daemon --project-cache-dir $projectCache
}
Invoke-VerificationGate 'classes' {
  .\gradlew.bat classes --no-daemon --project-cache-dir $projectCache
}
Invoke-VerificationGate 'appClasses' {
  .\gradlew.bat :app:classes --no-daemon --project-cache-dir $projectCache
}
Invoke-VerificationGate 'bootJar' {
  .\gradlew.bat bootJar --no-daemon --project-cache-dir $projectCache
}
$verification | ConvertTo-Json
~~~

각 명령의 exit code를 독립적으로 기록한다. 한 명령이 실패하면 후속
성공으로 덮지 않고 실행하지 않은 단계는 not_run으로 기록한다. 정확한
failing task를 보고한다. 광범위 test에서 stale class output 의심이 있고 referenced
class가 host-specific output에 실제로 존재하면 AGENTS.md의
scripts\verify_full_test_refresh.ps1 절차로 원인만 분리한다.

## 10. Phase F — fresh Browser happy-path

wrong-ID negative behavior는 Node fixture가 소유한다. Browser는 current
postimage의 정상 same-ID 복원과 stream/cancel 회귀만 확인한다.

아래 lifecycle은 8080/8081을 다시 읽고 어떤 기존 프로세스도 종료하지
않는다. 기존 listener는 모든 owner의 redacted command-line이 이 checkout의
exact path boundary를 가리키고, 각 process start time이 최신 target
postimage보다 늦거나 같고, trusted set에 8080이 있으며, fresh HTTP probe가
200이고 `/js/chat.js` 제공 bytes의 SHA-256이 현재 local chat.js와 정확히
같을 때만 사용한다. raw
command line은 출력하거나 저장하지 않는다. ownership predicate가 하나라도
실패하면 evidence_needed=port-owner-unproven, 소유권은 맞지만 readiness가
실패하면 evidence_needed=trusted-runtime-not-ready다.
HTTP는 준비됐지만 served bytes가 다르면
evidence_needed=trusted-runtime-stale-bytes다.

listener가 없을 때만 OS temp 아래 고정된 단일 receipt를 FileMode.CreateNew로
선점한 뒤 isolated cache의 bootRun을 한 번 시작한다. receipt는 Browser 도구
호출로 PowerShell 세션이 끊겨도 cleanup ownership을 복원하는 handoff다.
repo로 옮기거나 덮어쓰지 않는다. 충돌은 HOLD=boot-receipt-collision이다.
readiness stopwatch는 HTTP timeout과 sleep을 포함해 최대 60초다.

Start-Process가 반환한 직후 첫 receipt byte를 쓰기 전에 schema, owner,
canonical root, PID, exact process start time, launch-attempt time, redaction
boolean만 담은 bounded recovery packet을 출력한다. cleanup 성공까지 local
tool output을 보존한다. receipt write와 emergency cleanup이 모두 실패하면
partial JSON이 아니라 이 packet만 recovery authority다. PID/start-time과
exact root boundary를 다시 검증한 후에만 종료할 수 있으며 packet이 없으면
HOLD하고 추측해서 kill하지 않는다.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$env:AWX_AGENT_HOST = 'desktop-chat-restore'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-chat-restore'
$env:GRADLE_USER_HOME =
  Join-Path $env:USERPROFILE '.gradle-awx-desktop-chat-restore'
$projectCache = Join-Path $env:USERPROFILE `
  '.awx-gradle-project-cache\desktop-chat-restore'
New-Item -ItemType Directory -Force `
  -Path $env:GRADLE_USER_HOME,$projectCache | Out-Null
$currentChatHash = (
  Get-FileHash -Algorithm SHA256 `
    -LiteralPath (Join-Path $root 'main/resources/static/js/chat.js')
).Hash
$rootBoundaryPattern =
  '(?i)(?:^|[="''\s])' + [regex]::Escape($root) + '(?:[\\/"''\s]|$)'

Add-Type -AssemblyName System.Net.Http
function Get-UrlByteSha256 {
  param([string]$Uri,[int]$TimeoutSeconds)
  $client = [System.Net.Http.HttpClient]::new()
  $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
  try {
    $bytes = $client.GetByteArrayAsync($Uri).GetAwaiter().GetResult()
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
      return [BitConverter]::ToString($sha256.ComputeHash($bytes)).Replace('-','')
    } finally {
      $sha256.Dispose()
    }
  } finally {
    $client.Dispose()
  }
}

function Stop-OwnedProcessTreeVerified {
  param(
    [int]$RootProcessId,
    [string]$RootStartTimeUtc,
    [string]$CanonicalRoot,
    [string]$CanonicalRootBoundaryPattern
  )
  $rootStart = [DateTime]::Parse(
    $RootStartTimeUtc,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::RoundtripKind
  ).ToUniversalTime()
  $ownedProcessIds = [System.Collections.Generic.List[int]]::new()
  $seenProcessIds = [System.Collections.Generic.HashSet[int]]::new()
  $identityByProcessId = @{}
  $null = $ownedProcessIds.Add($RootProcessId)
  $null = $seenProcessIds.Add($RootProcessId)
  $identityByProcessId[$RootProcessId] = $RootStartTimeUtc
  $stopFailureCount = 0
  $stableEmptyRounds = 0

  for ($round = 0; $round -lt 6; $round++) {
    $addedThisRound = 0
    do {
      $addedInPass = 0
      $processRows = @(Get-CimInstance Win32_Process -ErrorAction Stop)
      foreach ($processRow in $processRows) {
        $parentProcessId = [int]$processRow.ParentProcessId
        $childProcessId = [int]$processRow.ProcessId
        if ($seenProcessIds.Contains($parentProcessId) -and
            $seenProcessIds.Add($childProcessId)) {
          $null = $ownedProcessIds.Add($childProcessId)
          $childProcess = Get-Process -Id $childProcessId -ErrorAction SilentlyContinue
          if ($childProcess) {
            $childStart = $childProcess.StartTime.ToUniversalTime()
            if ($childStart -lt $rootStart) { return $false }
            $identityByProcessId[$childProcessId] = $childStart.ToString('o')
          }
          $addedInPass++
          $addedThisRound++
        }
      }
    } while ($addedInPass -gt 0)

    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
            $identityByProcessId[$ownedProcessId]) {
        return $false
      }
    }
    for ($index = $ownedProcessIds.Count - 1; $index -ge 0; $index--) {
      $ownedProcessId = $ownedProcessIds[$index]
      if (-not $identityByProcessId.ContainsKey($ownedProcessId)) { continue }
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if (-not $ownedProcess) { continue }
      if ($ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
          $identityByProcessId[$ownedProcessId]) {
        return $false
      }
      try {
        Stop-Process -InputObject $ownedProcess -Force -ErrorAction Stop
      } catch {
        $stillOwned = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
        if ($stillOwned -and
            $stillOwned.StartTime.ToUniversalTime().ToString('o') -ceq
              $identityByProcessId[$ownedProcessId]) {
          $stopFailureCount++
        }
      }
    }
    Start-Sleep -Milliseconds 200
    $remainingOwnedCount = 0
    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
            $identityByProcessId[$ownedProcessId]) {
        $remainingOwnedCount++
      }
    }
    if ($remainingOwnedCount -eq 0 -and $addedThisRound -eq 0) {
      $stableEmptyRounds++
      if ($stableEmptyRounds -ge 2) { break }
    } else {
      $stableEmptyRounds = 0
    }
  }

  $remainingOwnedCount = 0
  foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
    $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
    if ($ownedProcess -and
        $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
          $identityByProcessId[$ownedProcessId]) {
      $remainingOwnedCount++
    }
  }
  $freshOwnedListenerCount = @(
    Get-NetTCPConnection -State Listen -LocalPort 8080,8081 `
      -ErrorAction SilentlyContinue | ForEach-Object {
        $listenerProcessId = [int]$_.OwningProcess
        $listenerProcess = Get-Process -Id $listenerProcessId `
          -ErrorAction SilentlyContinue
        if ($listenerProcess -and
            $listenerProcess.StartTime.ToUniversalTime() -ge $rootStart) {
          $listenerRecord = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $listenerProcessId" -ErrorAction SilentlyContinue
          if ($listenerRecord -and [regex]::IsMatch(
                [string]$listenerRecord.CommandLine,
                $CanonicalRootBoundaryPattern
              )) {
            $_
          }
        }
      }
  ).Count
  return (
    $stopFailureCount -eq 0 -and
    $remainingOwnedCount -eq 0 -and
    $freshOwnedListenerCount -eq 0 -and
    $stableEmptyRounds -ge 2
  )
}

$receiptName = 'awx-chat-restore-identity-20260806.boot.json'
$receiptOwner = 'codex-chat-restore-identity-20260806'
$receiptSchema = 'awx.chat_restore.boot_receipt.v1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$bootReceiptPath = [IO.Path]::GetFullPath((Join-Path $tempRoot $receiptName))
$receiptParent = [IO.Path]::GetDirectoryName($bootReceiptPath).TrimEnd('\')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($tempRoot,$receiptParent) -or
    [IO.Path]::GetFileName($bootReceiptPath) -cne $receiptName) {
  throw 'boot-receipt-path-invalid'
}

$listeners = @(
  Get-NetTCPConnection -State Listen -LocalPort 8080,8081 `
    -ErrorAction SilentlyContinue |
    Select-Object LocalAddress,LocalPort,OwningProcess
)
$bootStartedByThisRun = $false
$browserRuntimeReady = $false
$trustedExistingRuntime = $false
$targetLatestWriteUtc = @(
  $targets | ForEach-Object {
    (Get-Item -LiteralPath (Join-Path $root $_)).LastWriteTimeUtc
  }
) | Sort-Object -Descending | Select-Object -First 1

if ($listeners.Count -gt 0) {
  $ownerProof = @(
    $listeners.OwningProcess | Sort-Object -Unique | ForEach-Object {
      $ownerProcessId = [int]$_
      $ownedByCanonicalRoot = $false
      $startedAfterTargets = $false
      try {
        $processRecord = Get-CimInstance Win32_Process `
          -Filter "ProcessId = $ownerProcessId" -ErrorAction Stop
        $processView = Get-Process -Id $ownerProcessId -ErrorAction Stop
        $commandLine = [string]$processRecord.CommandLine
        $ownedByCanonicalRoot = [regex]::IsMatch(
          $commandLine,$rootBoundaryPattern
        )
        $startedAfterTargets =
          $processView.StartTime.ToUniversalTime() -ge $targetLatestWriteUtc
      } catch {
        $ownedByCanonicalRoot = $false
        $startedAfterTargets = $false
      }
      [pscustomobject]@{
        ownedByCanonicalRoot = $ownedByCanonicalRoot
        startedAfterTargets = $startedAfterTargets
      }
    }
  )
  $trustedExistingRuntime =
    $ownerProof.Count -gt 0 -and
    @($ownerProof | Where-Object {
      -not $_.ownedByCanonicalRoot -or -not $_.startedAfterTargets
    }).Count -eq 0 -and
    @($listeners | Where-Object LocalPort -EQ 8080).Count -gt 0
  if (-not $trustedExistingRuntime) { throw 'port-owner-unproven' }

  try {
    $response = Invoke-WebRequest `
      -Uri 'http://127.0.0.1:8080/chat-ui' `
      -UseBasicParsing `
      -TimeoutSec 3
    if ([int]$response.StatusCode -ne 200) {
      throw 'trusted-runtime-not-ready'
    }
  } catch {
    throw 'trusted-runtime-not-ready'
  }
  try {
    $servedChatHash = Get-UrlByteSha256 `
      -Uri 'http://127.0.0.1:8080/js/chat.js' -TimeoutSeconds 3
  } catch {
    throw 'trusted-runtime-not-ready'
  }
  if ($servedChatHash -cne $currentChatHash) {
    throw 'trusted-runtime-stale-bytes'
  }
  $browserRuntimeReady = $true
} else {
  $receiptStream = $null
  try {
    $receiptStream = [IO.File]::Open(
      $bootReceiptPath,
      [IO.FileMode]::CreateNew,
      [IO.FileAccess]::Write,
      [IO.FileShare]::None
    )
  } catch [IO.IOException] {
    throw 'boot-receipt-collision'
  }

  $bootProcess = $null
  $bootProcessStartTimeUtc = $null
  $bootRecoveryPacket = $null
  $bootRecoveryPacketEmitted = $false
  $bootLaunchAttemptUtc = [DateTime]::UtcNow.ToString('o')
  $bootArgs = @(
    'bootRun',
    '--no-daemon',
    '--project-cache-dir',
    $projectCache
  )
  try {
    $bootProcess = Start-Process `
      -FilePath (Join-Path $root 'gradlew.bat') `
      -ArgumentList $bootArgs `
      -WorkingDirectory $root `
      -WindowStyle Hidden `
      -PassThru
    $bootStartedByThisRun = $true
    $bootProcessId = [int]$bootProcess.Id
    $bootProcessStartTimeUtc =
      $bootProcess.StartTime.ToUniversalTime().ToString('o')
    $bootRecoveryPacket = [ordered]@{
      schemaVersion = 'awx.chat_restore.boot_recovery.v1'
      owner = $receiptOwner
      canonicalRoot = $root
      processId = $bootProcessId
      processStartTimeUtc = $bootProcessStartTimeUtc
      launchAttemptTimeUtc = $bootLaunchAttemptUtc
      rawCommandLineStored = $false
    }
    $bootRecoveryPacket | ConvertTo-Json -Compress
    $bootRecoveryPacketEmitted = $true
    $receiptJson = [ordered]@{
      schemaVersion = $receiptSchema
      owner = $receiptOwner
      canonicalRoot = $root
      processId = $bootProcessId
      processStartTimeUtc = $bootProcessStartTimeUtc
    } | ConvertTo-Json -Compress
    $receiptBytes = [Text.UTF8Encoding]::new($false).GetBytes($receiptJson)
    $receiptStream.Write($receiptBytes,0,$receiptBytes.Length)
    $receiptStream.Flush($true)
  } catch {
    if ($null -ne $receiptStream) {
      $receiptStream.Dispose()
      $receiptStream = $null
    }
    $emergencyCleanupSucceeded = $true
    if ($null -ne $bootProcess) {
      if ([string]::IsNullOrWhiteSpace($bootProcessStartTimeUtc)) {
        try {
          $bootProcessStartTimeUtc = (
            Get-Process -Id $bootProcess.Id -ErrorAction Stop
          ).StartTime.ToUniversalTime().ToString('o')
        } catch {
          try {
            $processRecord = Get-CimInstance Win32_Process `
              -Filter "ProcessId = $($bootProcess.Id)" -ErrorAction Stop
            $bootProcessStartTimeUtc = (
              [DateTime]$processRecord.CreationDate
            ).ToUniversalTime().ToString('o')
          } catch {
            $emergencyCleanupSucceeded = $false
          }
        }
      }
      if ($null -eq $bootRecoveryPacket -and
          -not [string]::IsNullOrWhiteSpace($bootProcessStartTimeUtc)) {
        $bootRecoveryPacket = [ordered]@{
          schemaVersion = 'awx.chat_restore.boot_recovery.v1'
          owner = $receiptOwner
          canonicalRoot = $root
          processId = [int]$bootProcess.Id
          processStartTimeUtc = $bootProcessStartTimeUtc
          launchAttemptTimeUtc = $bootLaunchAttemptUtc
          rawCommandLineStored = $false
        }
      }
      if ($null -ne $bootRecoveryPacket -and
          -not $bootRecoveryPacketEmitted) {
        $bootRecoveryPacket | ConvertTo-Json -Compress
        $bootRecoveryPacketEmitted = $true
      }
      if ($emergencyCleanupSucceeded) {
        try {
          $emergencyCleanupSucceeded = Stop-OwnedProcessTreeVerified `
            -RootProcessId ([int]$bootProcess.Id) `
            -RootStartTimeUtc $bootProcessStartTimeUtc `
            -CanonicalRoot $root `
            -CanonicalRootBoundaryPattern $rootBoundaryPattern
        } catch {
          $emergencyCleanupSucceeded = $false
        }
      }
    }
    if ($emergencyCleanupSucceeded -and
        [StringComparer]::OrdinalIgnoreCase.Equals(
          [IO.Path]::GetDirectoryName($bootReceiptPath).TrimEnd('\'),$tempRoot
        ) -and [IO.Path]::GetFileName($bootReceiptPath) -ceq $receiptName) {
      [IO.File]::Delete($bootReceiptPath)
    }
    if (-not $emergencyCleanupSucceeded -or
        (Test-Path -LiteralPath $bootReceiptPath)) {
      if ($null -ne $bootRecoveryPacket) {
        [ordered]@{
          recovery = $bootRecoveryPacket
          reason = 'boot-start-or-receipt-write-failed-cleanup-needed'
          emergencyCleanupSucceeded = $emergencyCleanupSucceeded
          fixedReceiptMayBePartial = $true
        } | ConvertTo-Json -Depth 4 -Compress
      }
      throw 'boot-start-or-receipt-write-failed-cleanup-needed'
    }
    throw 'boot-start-or-receipt-write-failed'
  } finally {
    if ($null -ne $receiptStream) { $receiptStream.Dispose() }
  }

  $readinessClock = [Diagnostics.Stopwatch]::StartNew()
  $runtimeServedHashMismatch = $false
  while ($readinessClock.ElapsedMilliseconds -lt 60000) {
    $bootProcess.Refresh()
    if ($bootProcess.HasExited) { break }
    $remainingMs = 60000 - $readinessClock.ElapsedMilliseconds
    if ($remainingMs -lt 1000) { break }
    $requestTimeoutSec = [int][Math]::Min(
      3,
      [Math]::Floor($remainingMs / 1000)
    )
    try {
      $response = Invoke-WebRequest `
        -Uri 'http://127.0.0.1:8080/chat-ui' `
        -UseBasicParsing `
        -TimeoutSec $requestTimeoutSec
      if ([int]$response.StatusCode -eq 200) {
        $remainingMs = 60000 - $readinessClock.ElapsedMilliseconds
        if ($remainingMs -lt 1000) { break }
        $hashTimeoutSec = [int][Math]::Min(
          3,
          [Math]::Floor($remainingMs / 1000)
        )
        $servedChatHash = Get-UrlByteSha256 `
          -Uri 'http://127.0.0.1:8080/js/chat.js' `
          -TimeoutSeconds $hashTimeoutSec
        if ($servedChatHash -cne $currentChatHash) {
          $runtimeServedHashMismatch = $true
          break
        }
        $browserRuntimeReady = $true
        break
      }
    } catch {
    }
    $remainingMs = 60000 - $readinessClock.ElapsedMilliseconds
    if ($remainingMs -gt 0) {
      Start-Sleep -Milliseconds ([int][Math]::Min(500,$remainingMs))
    }
  }
  $readinessClock.Stop()
  [ordered]@{
    bootStartedByThisRun = $bootStartedByThisRun
    bootReceiptCreated = (Test-Path -LiteralPath $bootReceiptPath)
    browserRuntimeReady = $browserRuntimeReady
    servedChatHashMatchesPostimage =
      ($browserRuntimeReady -and -not $runtimeServedHashMismatch)
    processExited = $bootProcess.HasExited
    readinessElapsedMs = $readinessClock.ElapsedMilliseconds
  } | ConvertTo-Json
  if ($runtimeServedHashMismatch) {
    throw 'runtime-served-chat-hash-mismatch'
  }
}
~~~

browserRuntimeReady=false면 Browser 성공을 주장하지 않는다.
disposition=evidence_needed, holdReason=localhost-not-ready로 기록하고 아래
cleanup을 실행한다.

browser:control-in-app-browser를 사용해
http://127.0.0.1:8080/chat-ui 를 연다.

1. 정상 session을 만들거나 연다.
2. numeric session ID만 기록한다.
3. benign message 한 번을 보내 terminal UI state까지 기다린다.
4. reload한다.
5. 동일 session ID와 그 session의 transcript가 복원되는지 확인한다.
6. mode diagnostic에 다른 session ID가 나타나지 않는지 확인한다.
7. 새 stream 하나를 시작하고 Cancel을 한 번 실행한다.
8. UI가 계속 입력 가능하고 stale transcript가 섞이지 않는지 확인한다.

기록 가능한 공개 증거:

- pageReachable boolean
- originalSessionId numeric
- restoredSessionId numeric
- sameIdentity boolean
- transcriptRestored boolean
- wrongModeIdVisible boolean
- cancelResponsive boolean

raw prompt, raw answer, cookie, request header, response body, provider payload는
저장하지 않는다. Browser가 막히면 로컬 Node/Gradle green을 Browser
success로 승격하지 말고 disposition=evidence_needed로 유지한다.

페이지가 열렸지만 same-ID restore, transcript, wrong-mode-ID, stream,
Cancel 중 하나가 실제 실패하면 browserHappyPath=FAIL,
disposition=HOLD, holdReason=browser-regression-observed로 기록한다.

startup 출력이 bootStartedByThisRun=true이면 Browser 관찰 후 별도
PowerShell 호출에서 아래 cleanup을 실행한다. readiness 또는 Browser 실패
후에도 실행한다. 고정 temp receipt로만 ownership을 복원하고, 어떤 stop보다
먼저 모든 캡처된 PID/start-time pair를 검증한다. descendant부터 root 순서로
종료한 뒤 모든 owned identity가 사라지고 캡처된 PID가 8080/8081을 더는
소유하지 않는지 확인한다. 성공 후에만 정확히 검증된 receipt 한 파일을
삭제한다. trusted existing runtime은 receipt를 만들지 않으며 종료 금지다.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$receiptName = 'awx-chat-restore-identity-20260806.boot.json'
$receiptOwner = 'codex-chat-restore-identity-20260806'
$receiptSchema = 'awx.chat_restore.boot_receipt.v1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$bootReceiptPath = [IO.Path]::GetFullPath((Join-Path $tempRoot $receiptName))
$receiptParent = [IO.Path]::GetDirectoryName($bootReceiptPath).TrimEnd('\')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($tempRoot,$receiptParent) -or
    [IO.Path]::GetFileName($bootReceiptPath) -cne $receiptName) {
  throw 'boot-receipt-path-invalid'
}
if (-not (Test-Path -LiteralPath $bootReceiptPath -PathType Leaf)) {
  throw 'boot-receipt-missing'
}

try {
  $receipt = Get-Content -LiteralPath $bootReceiptPath -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
} catch {
  throw 'boot-receipt-invalid'
}
if ($receipt.schemaVersion -cne $receiptSchema -or
    $receipt.owner -cne $receiptOwner -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$receipt.canonicalRoot,$root
    )) {
  throw 'boot-receipt-owner-mismatch'
}

$rootBoundaryPattern =
  '(?i)(?:^|[="''\s])' + [regex]::Escape($root) + '(?:[\\/"''\s]|$)'
function Stop-OwnedProcessTreeVerified {
  param(
    [int]$RootProcessId,
    [string]$RootStartTimeUtc,
    [string]$CanonicalRootBoundaryPattern
  )
  $rootStart = [DateTime]::Parse(
    $RootStartTimeUtc,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::RoundtripKind
  ).ToUniversalTime()
  $ownedProcessIds = [System.Collections.Generic.List[int]]::new()
  $seenProcessIds = [System.Collections.Generic.HashSet[int]]::new()
  $identityByProcessId = @{}
  $null = $ownedProcessIds.Add($RootProcessId)
  $null = $seenProcessIds.Add($RootProcessId)
  $identityByProcessId[$RootProcessId] = $RootStartTimeUtc
  $stopFailureCount = 0
  $stableEmptyRounds = 0

  for ($round = 0; $round -lt 6; $round++) {
    $addedThisRound = 0
    do {
      $addedInPass = 0
      $processRows = @(Get-CimInstance Win32_Process -ErrorAction Stop)
      foreach ($processRow in $processRows) {
        $parentProcessId = [int]$processRow.ParentProcessId
        $childProcessId = [int]$processRow.ProcessId
        if ($seenProcessIds.Contains($parentProcessId) -and
            $seenProcessIds.Add($childProcessId)) {
          $null = $ownedProcessIds.Add($childProcessId)
          $childProcess = Get-Process -Id $childProcessId -ErrorAction SilentlyContinue
          if ($childProcess) {
            $childStart = $childProcess.StartTime.ToUniversalTime()
            if ($childStart -lt $rootStart) { return $false }
            $identityByProcessId[$childProcessId] = $childStart.ToString('o')
          }
          $addedInPass++
          $addedThisRound++
        }
      }
    } while ($addedInPass -gt 0)

    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
            $identityByProcessId[$ownedProcessId]) {
        return $false
      }
    }
    for ($index = $ownedProcessIds.Count - 1; $index -ge 0; $index--) {
      $ownedProcessId = $ownedProcessIds[$index]
      if (-not $identityByProcessId.ContainsKey($ownedProcessId)) { continue }
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if (-not $ownedProcess) { continue }
      if ($ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
          $identityByProcessId[$ownedProcessId]) {
        return $false
      }
      try {
        Stop-Process -InputObject $ownedProcess -Force -ErrorAction Stop
      } catch {
        $stillOwned = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
        if ($stillOwned -and
            $stillOwned.StartTime.ToUniversalTime().ToString('o') -ceq
              $identityByProcessId[$ownedProcessId]) {
          $stopFailureCount++
        }
      }
    }
    Start-Sleep -Milliseconds 200
    $remainingOwnedCount = 0
    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
            $identityByProcessId[$ownedProcessId]) {
        $remainingOwnedCount++
      }
    }
    if ($remainingOwnedCount -eq 0 -and $addedThisRound -eq 0) {
      $stableEmptyRounds++
      if ($stableEmptyRounds -ge 2) { break }
    } else {
      $stableEmptyRounds = 0
    }
  }

  $remainingOwnedCount = 0
  foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
    $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
    if ($ownedProcess -and
        $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
          $identityByProcessId[$ownedProcessId]) {
      $remainingOwnedCount++
    }
  }
  $freshOwnedListenerCount = @(
    Get-NetTCPConnection -State Listen -LocalPort 8080,8081 `
      -ErrorAction SilentlyContinue | ForEach-Object {
        $listenerProcessId = [int]$_.OwningProcess
        $listenerProcess = Get-Process -Id $listenerProcessId `
          -ErrorAction SilentlyContinue
        if ($listenerProcess -and
            $listenerProcess.StartTime.ToUniversalTime() -ge $rootStart) {
          $listenerRecord = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $listenerProcessId" -ErrorAction SilentlyContinue
          if ($listenerRecord -and [regex]::IsMatch(
                [string]$listenerRecord.CommandLine,
                $CanonicalRootBoundaryPattern
              )) {
            $_
          }
        }
      }
  ).Count
  return (
    $stopFailureCount -eq 0 -and
    $remainingOwnedCount -eq 0 -and
    $freshOwnedListenerCount -eq 0 -and
    $stableEmptyRounds -ge 2
  )
}

$cleanupSucceeded = Stop-OwnedProcessTreeVerified `
  -RootProcessId ([int]$receipt.processId) `
  -RootStartTimeUtc ([string]$receipt.processStartTimeUtc) `
  -CanonicalRootBoundaryPattern $rootBoundaryPattern
if (-not $cleanupSucceeded) { throw 'boot-process-cleanup-failed' }

[IO.File]::Delete($bootReceiptPath)
if (Test-Path -LiteralPath $bootReceiptPath) {
  throw 'boot-receipt-cleanup-failed'
}
[ordered]@{
  processTreeCleanupVerified = $true
  bootReceiptDeleted = $true
} | ConvertTo-Json
~~~

## 11. Phase G — secret, postimage, Git, lease

고신뢰 count-only secret scan:

~~~powershell
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$secretPattern = @'
(?im)(api[_-]?key|client[_-]?secret|password)\s*[:=]\s*["'][A-Za-z0-9+/=_-]{12,}["']|authorization\s*:\s*["']Bearer\s+[A-Za-z0-9._-]{12,}["']|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]+PRIVATE KEY-----|sk-[A-Za-z0-9_-]{16,}|sb_(?:secret|publishable)_[A-Za-z0-9._-]{10,}|sbp_[A-Za-z0-9_-]{10,}
'@
$secretCount = 0
foreach ($target in $targets) {
  $text = Get-Content -LiteralPath $target -Raw -Encoding UTF8
  $secretCount += [regex]::Matches($text,$secretPattern).Count
}

$allowedTargetPaths = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}
try {
  $auditReceipt = Get-Content -LiteralPath $auditReceiptPath -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
} catch {
  throw 'audit-receipt-invalid'
}
if ($auditReceipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
    $auditReceipt.owner -cne 'codex-chat-restore-identity-20260806' -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$auditReceipt.canonicalRoot,$root
    )) {
  throw 'audit-receipt-owner-mismatch'
}
$baselineHead = [string]$auditReceipt.baselineHead
$preimageChatHash = [string]@(
  $auditReceipt.targetHashes |
    Where-Object path -CEQ 'main/resources/static/js/chat.js'
)[0].hash
$preimageFixtureHash = [string]@(
  $auditReceipt.targetHashes |
    Where-Object path -CEQ 'scripts/chat_ui_stream_contract_tests.js'
)[0].hash

function Get-PorcelainZEntries {
  param([string]$RepositoryRoot)
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = 'git'
  $startInfo.Arguments =
    '-c core.quotepath=false status --porcelain=v1 -z --untracked-files=all'
  $startInfo.WorkingDirectory = $RepositoryRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  try {
    if (-not $process.Start()) { throw 'git-status-start-failed' }
    $rawStatus = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'git-status-failed' }
  } finally {
    $process.Dispose()
  }
  $records = @(
    $rawStatus.Split(
      [char[]]@([char]0),[StringSplitOptions]::RemoveEmptyEntries
    )
  )
  $entries = [System.Collections.Generic.List[object]]::new()
  for ($index = 0; $index -lt $records.Count; $index++) {
    $record = [string]$records[$index]
    if ($record.Length -lt 4) { throw 'git-status-record-invalid' }
    $status = $record.Substring(0,2)
    $paths = [System.Collections.Generic.List[string]]::new()
    $paths.Add($record.Substring(3).Replace('\','/'))
    if ($status.IndexOf('R') -ge 0 -or $status.IndexOf('C') -ge 0) {
      $index++
      if ($index -ge $records.Count) { throw 'git-status-rename-pair-missing' }
      $paths.Add(([string]$records[$index]).Replace('\','/'))
    }
    $entries.Add([pscustomobject]@{ status = $status; paths = $paths.ToArray() })
  }
  return $entries.ToArray()
}
function Get-GitEntrySignature {
  param([object]$Entry)
  return [ordered]@{
    status = [string]$Entry.status
    paths = @($Entry.paths | ForEach-Object { [string]$_ })
  } | ConvertTo-Json -Compress
}

$finalHead = git rev-parse HEAD
$finalGitEntries = @(Get-PorcelainZEntries -RepositoryRoot $root)
$baselineSignatures = @(
  $auditReceipt.baselineGitEntries |
    ForEach-Object { Get-GitEntrySignature -Entry $_ }
)
$finalSignatures = @(
  $finalGitEntries | ForEach-Object { Get-GitEntrySignature -Entry $_ }
)
$statusDelta = @(
  Compare-Object `
    -ReferenceObject $baselineSignatures `
    -DifferenceObject $finalSignatures
)
$outOfScopeStatusDelta = @(
  $statusDelta | Where-Object {
    try {
      $deltaEntry = [string]$_.InputObject | ConvertFrom-Json -ErrorAction Stop
      $deltaPaths = @($deltaEntry.paths | ForEach-Object { [string]$_ })
      $deltaPaths.Count -eq 0 -or @(
        $deltaPaths | Where-Object { $allowedTargetPaths -notcontains $_ }
      ).Count -gt 0
    } catch {
      $true
    }
  }
)
$postimageHashes = @(
  $targets | ForEach-Object {
    [pscustomobject]@{
      path = $_
      hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
    }
  }
)
$postimageChatHash = [string]$postimageHashes[0].hash
$postimageFixtureHash = [string]$postimageHashes[1].hash
$sourceDiffCreated = $postimageChatHash -cne $preimageChatHash
$testDiffCreated = $postimageFixtureHash -cne $preimageFixtureHash
$targetDeletedCount = @(
  $targets | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
).Count
$commitCreated = $finalHead -cne $baselineHead

$finalState = [ordered]@{
  secretPatternCount = $secretCount
  postimageHashes = $postimageHashes
  targetStatus = @(git status --short -- $targets)
  fullStatusEntryCount = $finalGitEntries.Count
  outOfScopeStatusDeltaCount = $outOfScopeStatusDelta.Count
  sourceDiffCreated = $sourceDiffCreated
  testDiffCreated = $testDiffCreated
  targetDeletedCount = $targetDeletedCount
  stagedCount = @((git diff --cached --name-only)).Count
  indexLock = (Test-Path -LiteralPath '.git\index.lock')
  branch = (git branch --show-current)
  baselineHead = $baselineHead
  finalHead = $finalHead
  commitCreated = $commitCreated
  auditReceiptDeleted = $false
}
$auditReceiptJsonForDeletion = $auditReceipt | ConvertTo-Json -Depth 8 -Compress
if ([string]::IsNullOrWhiteSpace($auditReceiptJsonForDeletion)) {
  throw 'audit-receipt-invalid'
}
[IO.File]::Delete($auditReceiptPath)
if (Test-Path -LiteralPath $auditReceiptPath) {
  throw 'audit-receipt-cleanup-failed'
}
$finalState.auditReceiptDeleted = $true
$finalState | ConvertTo-Json -Depth 5
~~~

필수:

- secretPatternCount=0
- stagedCount=0
- indexLock=false
- targetDeletedCount=0
- outOfScopeStatusDeltaCount=0
- target hashes가 Phase E/F 동안 안정
- commitCreated=false
- auditReceiptDeleted=true

verified_no_patch_needed에서는 두 diff boolean이 모두 false여야 한다.
test_contract_added_and_verified에서는 sourceDiffCreated=false,
testDiffCreated=true여야 한다. patched_and_verified에서는
sourceDiffCreated=true여야 한다.

Phase D에서 lease를 얻었으면 성공/실패와 무관하게 finally에서 정확한
owner로 해제한다.

~~~powershell
$leaseArgs = @{
  Action = 'end'
  Role = 'desktop'
  Root = 'C:\AbandonWare\demo-1\demo-1\src'
  Topic = 'chat-restore-identity-postprocess'
  OwnerId = 'codex-chat-restore-identity-20260806'
}
& '.\__patch_drop__\source_edit_session.ps1' @leaseArgs
~~~

no-op path에서는 lease를 만들지 않는다. status만 확인해 다른 owner의
lease를 건드리지 않는다.

## 12. 실패와 롤백

- source patch 전 RED가 재현되지 않으면 no_patch_needed 또는 HOLD다.
- unrelated Node/Gradle failure는 이 결함의 증거로 바꾸지 않는다.
- source patch 뒤 GREEN이 실패하면 이 세션이 적용한 exact hunk만
  apply_patch inverse로 제거한다.
- 새 test block을 추가했다면 그 block만 제거한다.
- pre-existing restoreIdentityCases와 더 넓은 사용자 assertions는 삭제하지
  않는다.
- git reset, git checkout, stash, file replacement를 사용하지 않는다.
- rollback 후에도 postimage hash, stagedCount, indexLock, lease release를
  보고한다.

성공 Phase G가 audit receipt를 삭제하기 전에 종료하는 모든 HOLD,
evidence_needed, 검증 실패 경로는 bounded hash/count/reason을 먼저 보고에
옮긴 뒤 아래 self-contained cleanup을 실행한다.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$receiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$receiptPath = [IO.Path]::GetFullPath((Join-Path $tempRoot $receiptName))
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($receiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($receiptPath) -cne $receiptName) {
  throw 'audit-receipt-path-invalid'
}
if (Test-Path -LiteralPath $receiptPath -PathType Leaf) {
  try {
    $receipt = Get-Content -LiteralPath $receiptPath -Raw -Encoding UTF8 |
      ConvertFrom-Json -ErrorAction Stop
  } catch {
    throw 'audit-receipt-invalid'
  }
  if ($receipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
      $receipt.owner -cne 'codex-chat-restore-identity-20260806' -or
      -not [StringComparer]::OrdinalIgnoreCase.Equals(
        [string]$receipt.canonicalRoot,$root
      )) {
    throw 'audit-receipt-owner-mismatch'
  }
  [IO.File]::Delete($receiptPath)
  if (Test-Path -LiteralPath $receiptPath) {
    throw 'audit-receipt-cleanup-failed'
  }
}
~~~

## 13. 완료 판정

### patched_and_verified

다음을 모두 만족할 때만 사용한다.

- RED가 취약 동작으로 실패
- 승인된 최소 source diff가 실제 적용
- Node GREEN
- focused JUnit PASS
- chatUiTest PASS
- classes PASS
- :app:classes PASS
- bootJar PASS
- Browser same-ID restore/cancel PASS
- browserServedChatHashMatchesPostimage=true
- bootReceiptDeleted=true 또는 not_created
- secretPatternCount=0
- sourceDiffCreated=true
- targetDeletedCount=0
- outOfScopeStatusDeltaCount=0
- stagedCount=0
- commitCreated=false
- source lease released

### test_contract_added_and_verified

다음을 모두 만족할 때만 사용한다.

- source guard는 실행 시작 전에 이미 정확
- 동등한 wrong-ID fixture만 없었음
- fixture 한 파일에 characterization contract만 추가
- sourceDiffCreated=false
- testDiffCreated=true
- Node PASS
- focused/broad Gradle ladder PASS
- Browser same-ID restore/cancel PASS
- browserServedChatHashMatchesPostimage=true
- bootReceiptDeleted=true 또는 not_created
- secretPatternCount=0
- targetDeletedCount=0
- outOfScopeStatusDeltaCount=0
- stagedCount=0
- commitCreated=false
- source lease not_acquired

### verified_no_patch_needed

다음을 모두 만족할 때만 사용한다.

- live source가 shared validator guard를 이미 포함
- wrong-ID atomic fixture가 존재
- Node PASS
- target hashes가 검증 동안 안정
- focused/broad Gradle ladder PASS
- Browser same-ID restore/cancel PASS
- browserServedChatHashMatchesPostimage=true
- bootReceiptDeleted=true 또는 not_created
- sourceDiffCreated=false
- testDiffCreated=false
- targetDeletedCount=0
- outOfScopeStatusDeltaCount=0
- stagedCount=0
- commitCreated=false

### evidence_needed

local contract와 build는 green이지만 Browser runtime/port/current-postimage
proof 같은 필수 외부 증거 하나가 없을 때 사용한다. exact command 한 개를
nextSingleProof로 적는다.

Browser가 실제 실행되어 회귀를 관찰한 경우는 evidence_needed가 아니라
browserHappyPath=FAIL과 HOLD browser-regression-observed다.

### HOLD

ownership, hash stability, preflight order, scenario coverage, lease, index
lock, PatchDrop, branch, active owner, Browser regression, unrelated
regression 중 하나가
막혔을 때 사용한다. 소스를 수정하지 않거나 exact local hunk만 롤백한다.

## 14. 고정 최종 보고

아래 key를 모두 실제 관찰값으로 한 줄씩 출력한다. 선택지 문구나 변수명을
그대로 남기지 않는다.

- directiveId: AWX-CHAT-RESTORE-IDENTITY-P0-20260806
- disposition: patched_and_verified, test_contract_added_and_verified,
  verified_no_patch_needed, HOLD, evidence_needed 중 하나
- activeTargets: 정확한 두 repo-relative path
- preimage.chatJs: 실행 시 SHA-256
- preimage.nodeFixture: 실행 시 SHA-256
- postimage.chatJs: 종료 시 SHA-256
- postimage.nodeFixture: 종료 시 SHA-256
- sourceDiffCreated: true 또는 false
- testDiffCreated: true 또는 false
- nodeContract: PASS 또는 FAIL
- focusedJUnit: PASS, FAIL, not_run 중 하나
- chatUiTest: PASS, FAIL, not_run 중 하나
- classes: PASS, FAIL, not_run 중 하나
- appClasses: PASS, FAIL, not_run 중 하나
- bootJar: PASS, FAIL, not_run 중 하나
- browserHappyPath: PASS, FAIL, evidence_needed 중 하나
- browserSameIdentity: true, false, not_observed 중 하나
- browserServedChatHashMatchesPostimage: true, false, not_observed 중 하나
- bootReceiptDeleted: true, false, not_created 중 하나
- secretPatternCount: 실제 정수
- baselineHead: 실행 시작 시 실제 hash
- finalHead: 실행 종료 시 실제 hash
- outOfScopeStatusDeltaCount: 실제 정수이며 완료 시 0
- targetDeletedCount: 실제 정수이며 완료 시 0
- stagedCount: 실제 정수이며 완료 시 0
- indexLock: true 또는 false
- commitCreated: baselineHead와 finalHead 비교에서 계산한 true 또는 false
- auditReceiptDeleted: true 또는 false
- sourceLeaseReleased: true, false, not_acquired 중 하나
- holdReason: fixed reason code 또는 none
- nextSingleProof: exact command 또는 none

관찰하지 않은 검증을 PASS로 쓰지 않는다. UI에 답이 보였다는 사실만으로
provider, wire attempt, semantic identity 계약을 증명했다고 쓰지 않는다.

## 15. HOLD_FOLLOWUP — 이번 실행에서 수정 금지

다음은 별도 후보이며 이 지시서의 active backlog가 아니다.

1. ChatApiController의 중복된 손상·mojibake missing-session 응답 문구
2. terminal session-list refresh가 이전 in-flight refresh에 흡수될 가능성
3. interactive selection 실패의 접근 가능한 고정 오류 알림 부재

각 후보는 새 current evidence, 새 RED, 새 승인, 새 target ownership을
필요로 한다. 이 세 후보를 이유로 현재 두 파일 밖을 수정하지 않는다.

## 16. 종료 규칙

최종 보고 전 superpowers:verification-before-completion을 실행한다.
그 skill이 요구하는 fresh command output을 사용하고 과거 요약을 proof로
재사용하지 않는다.

이 지시서 자체나 설계/계획 문서를 수정하지 않는다. 실행 결과는 대화의
최종 보고로만 반환하며, 사용자가 별도 출력 경로를 지정하지 않은 한 새
보고서 파일을 만들지 않는다.

완료하거나 정확한 blocker를 증명한 뒤 더 넓은 탐침을 계속하지 않는다.

[DONE]
