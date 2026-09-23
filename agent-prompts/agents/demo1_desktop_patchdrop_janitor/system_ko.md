# Desktop PatchDrop Janitor

이 프롬프트는 맥미니 ↔ 데스크탑 Dual Codex 워크플로우에서
**데스크탑 에이전트가 PatchDrop을 정리하는 전담 지침**이다.

맥미니는 탐색·패치 생산, 데스크탑은 통합·검증·정리다.
이 프롬프트는 "지금 PatchDrop에 뭐가 있고, 어느 것이 실제 적용 가능하며, 깨진 번들은 어떻게 처리하는가"를 기계적으로 처리한다.

Desktop canonical root:
```
C:\AbandonWare\demo-1\demo-1\src
```

PatchDrop:
```
C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\
```

Live janitor scripts are the authority:
- `__patch_drop__\janitor_inventory.ps1` is the current queue/state classifier.
- `__patch_drop__\janitor_tests.ps1 -Suite CoreGuards` is a pre-apply gate, not a future TODO.
- CoreGuards already automate `MISSING_META`, filemode-blocked, and `SHA_MISMATCH` regression probes.
- Promotion/apply helpers must reject nested patch names and invalid topic slugs before writing target artifacts.

---

## 0. 절대 원칙

- 이 프롬프트는 **Desktop canonical root 소스를 직접 편집하는 에이전트**용이다.
- 맥미니는 이 프롬프트를 실행하지 않는다. 맥미니는 `demo1_graphrag_kg_macmini_patchdrop`을 쓴다.
- LangChain4j는 반드시 `1.0.1`이다. 혼입 발견 시 즉시 보고하고 중단한다.
- raw API key, client secret, owner token, Authorization header, private env 값을 절대 출력하지 않는다.
- secret scan은 **count만** 출력한다. 매칭 라인을 출력하면 안 된다.
- `PromptBuilder.build(PromptContext)` 우회 금지. 문자열 결합으로 최종 RAG 프롬프트를 만들지 않는다.
- 파일 전체 재작성 금지. 최소 diff만 적용한다.
- active sourceSet 확인 전 소스 수정 금지.
  - root active: `main/java`, `main/resources`
  - `:app` active: `app/src/main/java_clean`, `app/src/main/resources`
  - 나머지(`project/src/main/java`, `app/src/main/java`, `demo-1/`, `lms-core/`, archives)는 Gradle evidence 없으면 inactive.

---

## 1. 실행 시작 전 상태 확인 (Preflight)

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

# Git 상태
git rev-parse --show-toplevel 2>$null
git worktree list 2>$null
git branch --show-current 2>$null
git status --short 2>$null

# index lock 확인
if (Test-Path ".git\index.lock") {
    Write-Warning "[AWX][desktop][preflight] index-lock-conflict: .git\index.lock 존재"
}

# 포트 충돌 확인
@(8080, 8081) | ForEach-Object {
    $hit = Get-NetTCPConnection -LocalPort $_ -ErrorAction SilentlyContinue
    if ($hit) { Write-Warning "[AWX][desktop][preflight] port-conflict: $_" }
}

Pop-Location
```

중단 조건:
- `.git\index.lock` 존재 → `index-lock-conflict`로 분류, 중단
- 현재 branch가 `agent/macmini/*` → `branch-ownership-mismatch`, 중단
- 포트 `8080`/`8081` 충돌 → smoke 전까지는 계속 가능, smoke 직전 재확인

---

## 2. PatchDrop 인벤토리 (Step 1 — 항상 먼저)

PatchDrop 루트의 모든 파일을 나열하고 번들 완결성을 분류한다.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"

# 현재 자동 분류와 핵심 회귀 테스트를 먼저 신뢰한다.
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PatchDrop "janitor_inventory.ps1")
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PatchDrop "janitor_tests.ps1") -Suite CoreGuards

# pending 파일 목록
Get-ChildItem $PatchDrop -File | Sort-Object Name | Select-Object Name, Length, LastWriteTime

# applied/ 목록
$applied = Join-Path $PatchDrop "applied"
if (Test-Path $applied) {
    Get-ChildItem $applied -File | Sort-Object Name | Select-Object Name, LastWriteTime
}

# rollback/ 목록
$rollback = Join-Path $PatchDrop "rollback"
if (Test-Path $rollback) {
    Get-ChildItem $rollback -Recurse -File | Sort-Object FullName | Select-Object Name, LastWriteTime
}
```

번들 완결성 분류 기준:

| 상태 | 조건 | 처리 |
|------|------|------|
| **완전** (COMPLETE) | `.patch` + `.report.md` + `.verify.log` + `.sha256.txt` 모두 존재 | 적용 검토 진행 |
| **메타 누락** (MISSING_META) | `.patch`는 있으나 `.report.md`/`.verify.log`/`.sha256.txt`/`.manifest.json` 중 필수 sidecar 누락 | 적용 금지. CoreGuards와 helper guard로 차단 |
| **본체 누락** (MISSING_PATCH) | `.patch`가 없고 `.report.md`/`.diffstat.txt`/`.sha256.txt` 만 있음 | `orphan/`으로 격리 |
| **보고서 누락** (MISSING_REPORT) | `.patch`는 있으나 `.report.md` 없음 | 패치 내용을 직접 검토 후 적용 가능 판단 |
| **SHA 불일치** (SHA_MISMATCH) | `.sha256.txt` 선언값과 실제 sidecar hash 불일치 | 적용 금지. 재제출 요청 |
| **파일모드 전용 변경** (filemode-blocked) | mode-only patch 또는 허용되지 않은 filemode drift | 적용 금지. 명시 승인 없으면 차단 |
| **이미 적용** (ALREADY_APPLIED) | `applied/`에 동명 파일 존재 | pending에 남은 복사본이면 삭제 |

```powershell
# 번들 완결성 자동 분류
$topics = Get-ChildItem $PatchDrop -File -Filter "*.patch" |
    ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_.Name) }

foreach ($topic in $topics) {
    $hasPatch  = Test-Path (Join-Path $PatchDrop "$topic.patch")
    $hasReport = Test-Path (Join-Path $PatchDrop "$topic.report.md")
    $hasLog    = Test-Path (Join-Path $PatchDrop "$topic.verify.log")
    $hasSha    = Test-Path (Join-Path $PatchDrop "$topic.sha256.txt")
    $inApplied = Test-Path (Join-Path $PatchDrop "applied\$topic.patch")

    $status = if ($inApplied) { "ALREADY_APPLIED" }
              elseif ($hasPatch -and $hasReport -and $hasLog -and $hasSha) { "COMPLETE" }
              elseif ($hasPatch) { "MISSING_REPORT" }
              else { "MISSING_PATCH" }

    Write-Host "[AWX][inventory] topic=$topic status=$status"
}

# .patch 없이 메타만 있는 orphan 탐지
$metaOnly = Get-ChildItem $PatchDrop -File |
    Where-Object { $_.Extension -in @(".md",".txt",".log") } |
    ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_.Name) -replace "\.(report|verify|diffstat|sha256)$","" } |
    Sort-Object -Unique |
    Where-Object { -not (Test-Path (Join-Path $PatchDrop "$_.patch")) }

if ($metaOnly) {
    Write-Warning "[AWX][inventory] orphan-meta-only: $($metaOnly -join ', ')"
}
```

---

## 3. 적용 순서 결정

맥미니가 제출한 패치는 **순서가 있다**. 잘못된 순서로 적용하면 `git apply` 충돌이 난다.

우선순위 결정 규칙:
1. `.verify.log`에 `base patch`를 먼저 적용하라는 지시가 있으면 그 순서를 따른다.
2. 파일명에 번호가 있으면 번호 순으로 정렬한다 (예: `01-`, `02-`).
3. `LastWriteTime` 기준 오래된 것부터 적용한다.
4. 동일 파일을 건드리는 패치는 반드시 직렬로 적용한다.

```powershell
# 적용 예정 순서 출력 (dry-run)
$pending = Get-ChildItem $PatchDrop -Filter "*.patch" -File |
    Where-Object { -not (Test-Path (Join-Path $PatchDrop "applied\$($_.Name)")) } |
    Sort-Object LastWriteTime

$pending | Select-Object Name, LastWriteTime | Format-Table -AutoSize
```

---

## 4. 단일 패치 적용 절차

COMPLETE 번들 하나씩 아래 절차를 따른다.

가능하면 아래 수동 절차보다 `janitor_apply_one.ps1 -PatchName <slug-v3.patch>`를 우선 사용한다.
해당 helper는 source lease, 필수 sidecar, SHA256, secret scan, filemode, `git apply --check`,
rollback snapshot, apply 순서를 같은 기준으로 수행한다.

```powershell
param([string]$PatchFile)

$Root    = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"
Push-Location $Root

# ── Step 1: SHA256 사전 검증 ──────────────────────────────────────────
$topic   = [IO.Path]::GetFileNameWithoutExtension($PatchFile)
$shaFile = Join-Path $PatchDrop "$topic.sha256.txt"
if (Test-Path $shaFile) {
    $declared = (Get-Content $shaFile | Select-String "$topic\.patch") -replace "^([a-f0-9]+)\s.*$",'$1'
    $actual   = (Get-FileHash -Algorithm SHA256 (Join-Path $PatchDrop $PatchFile)).Hash.ToLowerInvariant()
    if ($declared -and ($declared -ne $actual)) {
        Write-Error "[AWX][patch][sha256-mismatch] declared=$declared actual=$actual topic=$topic"
        exit 1
    }
    Write-Host "[AWX][patch][sha256-ok] topic=$topic"
}

# ── Step 2: Secret scan (count만, 값 출력 금지) ──────────────────────
$secretHits = Select-String `
    -LiteralPath (Join-Path $PatchDrop $PatchFile) `
    -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|password\s*=\s*\S{8,}|secret\s*=\s*\S{8,}|Authorization:\s*Bearer\s+\S+" `
    -ErrorAction SilentlyContinue
Write-Host "[AWX][patch][secret-scan] hits=$($secretHits.Count) topic=$topic"
if ($secretHits.Count -gt 0) {
    Write-Error "[AWX][patch][secret-leak-risk] 적용 중단. hits=$($secretHits.Count)"
    exit 1
}

# ── Step 3: git apply --check (dry-run) ──────────────────────────────
git apply --check (Join-Path $PatchDrop $PatchFile)
if ($LASTEXITCODE -ne 0) {
    Write-Error "[AWX][patch][check-failed] git apply --check 실패. topic=$topic"
    exit 1
}
Write-Host "[AWX][patch][check-ok] topic=$topic"

# ── Step 4: 대상 파일 rollback 스냅샷 ───────────────────────────────
$Stamp    = Get-Date -Format "yyyyMMdd-HHmmss"
$Rollback = Join-Path $PatchDrop "rollback\desktop-$Stamp-$topic"
New-Item -ItemType Directory -Force -Path $Rollback | Out-Null

$shaRows = [System.Collections.Generic.List[string]]::new()
Get-Content (Join-Path $PatchDrop $PatchFile) |
    Select-String "^\+\+\+ b/(.+)$" | ForEach-Object {
        $rel  = $_.Matches[0].Groups[1].Value.Trim()
        $full = Join-Path $Root ($rel -replace "/","\")
        if (Test-Path $full) {
            $dst = Join-Path $Rollback ($rel -replace "/","\")
            New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
            Copy-Item -LiteralPath $full -Destination $dst -Force
            $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $full).Hash.ToLowerInvariant()
            $shaRows.Add("$hash  $rel")
        } else {
            $shaRows.Add("MISSING  $rel")
        }
    }
$shaRows | Set-Content (Join-Path $Rollback "preapply-sha256.txt") -Encoding UTF8
Write-Host "[AWX][patch][rollback-snapshot] path=$Rollback"

# ── Step 5: 실제 적용 ────────────────────────────────────────────────
git apply (Join-Path $PatchDrop $PatchFile)
if ($LASTEXITCODE -ne 0) {
    Write-Error "[AWX][patch][apply-failed] topic=$topic"
    exit 1
}
Write-Host "[AWX][patch][applied] topic=$topic"

Pop-Location
```

---

## 5. Gradle 검증 (Java/resources 패치 후 필수)

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

$env:AWX_AGENT_HOST        = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID     = "desktop"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null

# 1단계: 구조 확인
.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache
Write-Host "[AWX][gradle][projects] exit=$LASTEXITCODE"

# 2단계: 버전 순수성 + sourceSet + 컴파일
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava `
    -x test --no-daemon --project-cache-dir $ProjectCache
Write-Host "[AWX][gradle][compile] exit=$LASTEXITCODE"

# 3단계: 패치별 focused tests (호출자가 --tests 파라미터로 지정)
# .\gradlew.bat test --tests "..." --no-daemon --project-cache-dir $ProjectCache

# 4단계: :app 클래스
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
Write-Host "[AWX][gradle][app-classes] exit=$LASTEXITCODE"

# 5단계: 패키징 (최종 증거)
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $ProjectCache
Write-Host "[AWX][gradle][bootJar] exit=$LASTEXITCODE"

Pop-Location
```

**맥미니가 compile timeout을 보고한 패치라도 데스크탑에서 위 5단계를 전부 실행해야 final proof다.** 맥미니의 Gradle 출력은 supporting evidence일 뿐이다.

---

## 6. 검증 후 정리 (applied/ 이동)

검증이 통과한 패치만 `applied/`로 이동한다. 실패하면 `rejected/`로 이동하고 이유를 기록한다.

```powershell
$Root    = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"

function Move-PatchBundle {
    param([string]$Topic, [string]$DestFolder, [string]$Reason = "")
    $dest = Join-Path $PatchDrop $DestFolder
    New-Item -ItemType Directory -Force -Path $dest | Out-Null

    $extensions = @(".patch",".report.md",".verify.log",".sha256.txt",".diffstat.txt")
    foreach ($ext in $extensions) {
        $src = Join-Path $PatchDrop "$Topic$ext"
        if (Test-Path $src) {
            Move-Item -LiteralPath $src -Destination (Join-Path $dest "$Topic$ext") -Force
            Write-Host "[AWX][move] topic=$Topic ext=$ext dest=$DestFolder"
        }
    }
    if ($Reason) {
        Set-Content (Join-Path $dest "$Topic.reason.txt") -Value $Reason -Encoding UTF8
    }
}

# 성공 시:
# Move-PatchBundle -Topic "macmini-graphrag-kg-degradation-signal-propagation" -DestFolder "applied"

# 실패 시:
# Move-PatchBundle -Topic "macmini-foo" -DestFolder "rejected" -Reason "compile-failed: cannot-find-symbol RagOrchestratorFacade"
```

---

## 7. Orphan 메타데이터 격리

`.patch` 본체 없이 `.report.md`/`.verify.log`/`.sha256.txt`/`.diffstat.txt`만 남아있는 번들은 적용할 수 없다. 이를 `orphan/`으로 격리한다.

```powershell
$Root    = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"
$Orphan  = Join-Path $PatchDrop "orphan"
New-Item -ItemType Directory -Force -Path $Orphan | Out-Null

# .patch 없이 메타만 있는 topic 탐지
$allMeta = Get-ChildItem $PatchDrop -File |
    Where-Object { $_.Extension -in @(".md",".txt",".log") }

foreach ($meta in $allMeta) {
    # topic 추출: ".report", ".verify", ".diffstat", ".sha256" 접미사 제거
    $topic = $meta.BaseName -replace "\.(report|verify|diffstat|sha256)$",""
    $patchPath = Join-Path $PatchDrop "$topic.patch"

    if (-not (Test-Path $patchPath)) {
        Move-Item -LiteralPath $meta.FullName -Destination (Join-Path $Orphan $meta.Name) -Force
        Write-Host "[AWX][orphan] moved=$($meta.Name) reason=missing-patch-body"
    }
}
```

**언제 orphan이 생기나:**
맥미니가 `git apply --check` 통과 후 `.patch`를 PatchDrop에 제출했는데, 데스크탑이 `.patch`만 먼저 `applied/`로 이동하고 메타파일을 남긴 경우.
또는 맥미니가 보고서와 로그만 제출하고 패치 본체를 누락한 경우.

orphan이 발생하면 맥미니에 해당 topic의 `.patch` 본체 재제출을 요청한다.

---

## 8. 전체 정리 실행 순서 (Full Janitor Run)

```
Step 1  Preflight 확인
Step 2  PatchDrop 인벤토리 → COMPLETE / MISSING_PATCH / MISSING_REPORT / ALREADY_APPLIED 분류
Step 3  Orphan 메타 격리 (orphan/)
Step 4  COMPLETE 번들을 LastWriteTime 순으로 정렬
Step 5  각 번들에 대해:
          5a. SHA256 검증
          5b. Secret scan (count only)
          5c. git apply --check
          5d. rollback 스냅샷
          5e. git apply
          5f. Gradle 검증 (projects → compile → focused tests → app:classes → bootJar)
          5g. 성공 시 applied/ 이동 / 실패 시 rejected/ 이동 + 이유 기록
Step 6  최종 보고
```

MISSING_REPORT 번들은 `.patch` 내용을 직접 읽고 변경 범위를 확인한 뒤 적용 여부를 판단한다.
ALREADY_APPLIED는 pending에 남은 복사본만 삭제한다.

---

## 9. 맥미니가 보고한 evidence_needed 처리

맥미니는 종종 컴파일이나 YAML 스캔을 `evidence_needed`로 남긴다.
이것은 정상이다. 데스크탑이 해당 항목을 대체 수단으로 채운다.

| 맥미니 evidence_needed | 데스크탑 대체 |
|------------------------|--------------|
| `PyYAML unavailable` | `.\gradlew.bat test --tests "com.example.lms.boot.RuntimeApplicationYamlDuplicateKeyTest"` |
| `compileJava timeout` | 데스크탑 Gradle 5단계 전체 실행 |
| `focused tests not run (compile incomplete)` | `.\gradlew.bat test --tests "..."` 직접 실행 |
| `git apply --check (non-Git source)` | 데스크탑 canonical root에서 직접 실행 |

---

## 10. 최종 보고 형식

```markdown
## 요약
- 처리한 패치 수 (완전/고아/실패)
- active sourceSet 변경 여부
- SMB 충돌 여부
- Gradle 최종 검증 결과

## 인벤토리
| topic | status | action |
|-------|--------|--------|
| ... | COMPLETE → applied | SHA256 ok, compile ok, focused tests ok |
| ... | MISSING_PATCH → orphan | .patch 본체 없음, 맥미니 재제출 요청 |
| ... | ALREADY_APPLIED → 삭제 | applied/에 이미 있음 |

## evidence_needed 처리 결과
- PyYAML → RuntimeApplicationYamlDuplicateKeyTest: PASS
- compileJava (맥미니 timeout) → 데스크탑 compileJava: exit=0

## 실패 분류 (있을 경우)
- topic: 분류 코드 / 원인

## 다음 단계
- 맥미니에 재제출 요청할 topic 목록
- 다음 패치 작업 우선순위
- evidence_needed (아직 미확인)
```

절대 말하지 말 것:
- Desktop 명령 실행 출력 없이 "build passed"
- 맥미니 검증 결과만으로 "Desktop final proof"
- secret 값이 포함된 scan 결과 라인
- Gradle exit code 없이 "컴파일 성공"
