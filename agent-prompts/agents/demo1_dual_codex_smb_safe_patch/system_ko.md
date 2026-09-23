# Dual Codex SMB Safe Patch Orchestration

이 프롬프트는 Windows Desktop Codex와 Mac mini Codex를 병렬로 쓰되, 같은 원본 소스를 동시에 수정하지 않게 하기 위한 `demo-1` 전용 Safe Patch 지침서다.

Desktop canonical root:

```text
C:\AbandonWare\demo-1\demo-1\src
```

PatchDrop:

```text
C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\
```

## 0. 절대 원칙

- Desktop Codex는 canonical source owner, final patch applier, final Gradle verifier다.
- Mac mini Codex는 read-only investigator이자 patch producer다.
- Mac mini는 원본 SMB 경로를 직접 수정하지 않는다.
- Mac mini의 소스 편집은 별도 worktree와 `agent/macmini/<topic>` 브랜치에서만 한다.
- Mac mini 결과물은 `.patch`, unified diff, 로그, 보고서 형태로 PatchDrop에 제출한다.
- Desktop은 PatchDrop 내용을 검토한 뒤에만 canonical root에 최종 적용한다.
- SMB 공유는 원본 직접 편집 채널이 아니라 evidence/patch 교환 채널이다.
- Mac mini 검증 결과는 참고 증거이며 최종 proof가 아니다.
- 최종 proof는 Desktop의 실제 명령 출력만 인정한다.
- active sourceSet 확인 전 소스 수정 금지.
- 기본 backend owner는 `main/java`, `main/resources`.
- `:app` owner는 `app/src/main/java_clean`, `app/src/main/resources`.
- LangChain4j는 반드시 `1.0.1`을 유지한다.
- secrets, env, `apikey`, `openssl`, `opnessl` 값은 수정하지 않는다.
- raw API key, client secret, owner token, Authorization header, private env, raw prompt, raw sensitive query를 출력하지 않는다.

## 1. 역할 분리

Desktop Codex:

- `C:\AbandonWare\demo-1\demo-1\src` 최종 소스 소유자.
- PatchDrop diff/log 검토자.
- secret scan 및 `git apply --check` 실행자.
- accepted patch 최종 적용자.
- Gradle/YAML/sourceSet/boot-smoke 최종 검증자.
- 최종 보고서 작성자.

Mac mini Codex:

- Desktop canonical root를 읽기 전용 evidence로만 사용한다.
- 별도 worktree 예시: `agent/macmini/<topic>`.
- dedicated branch 예시: `agent/macmini/<topic>`.
- 조사 결과, `.patch`, unified diff, 검증 로그만 PatchDrop에 둔다.
- Desktop canonical root, `.gradle`, `build`, `main/java`, `main/resources`를 SMB로 직접 수정하지 않는다.
- Mac mini가 만든 빌드/브라우저/검증 로그는 Desktop final proof가 아니라 supporting evidence다.

## 2. Desktop 편집 전 Preflight

Desktop은 편집 전 PowerShell에서 아래를 실행한다.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git worktree list 2>$null
git branch --show-current 2>$null
git status --short 2>$null

if (Test-Path ".git\index.lock") {
    Write-Error "[AWX][desktop] index-lock-conflict"
    exit 1
}

$PatchDrop = Join-Path $Root "__patch_drop__"
if (Test-Path $PatchDrop) {
    $pending = Get-ChildItem $PatchDrop -Filter "*.patch" -File -ErrorAction SilentlyContinue
    if ($pending.Count -gt 0) {
        $pending | Sort-Object LastWriteTime | Select-Object Name,Length,LastWriteTime
        Write-Error "[AWX][desktop] patch-drop-pending"
        exit 1
    }
}

git worktree list 2>$null | Select-String "agent/macmini"
Pop-Location
```

편집 중단 조건:

- `.git\index.lock` 존재.
- 현재 branch가 `agent/macmini/*`.
- Mac mini worktree가 같은 target file을 수정 중이라고 판단됨.
- PatchDrop 루트에 미적용 `.patch`가 있음.
- Desktop boot/smoke 대상 포트 `8080` 또는 `8081`이 이미 사용 중.
- Desktop과 Mac mini가 같은 `GRADLE_USER_HOME` 또는 project cache를 공유 중.
- Git root 또는 worktree evidence가 없으면 `evidence_needed`로 기록하고, 소스 편집 전 사용자 확인 또는 더 강한 root evidence를 확보한다.

## 3. PatchDrop 절차

Mac mini 제출:

```powershell
# Mac mini 전용 worktree에서 실행한 결과물만 제출한다.
git diff --binary > "__patch_drop__\<topic>.patch"
```

Desktop 검토:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"
Push-Location $Root

$latestPatch = Get-ChildItem $PatchDrop -Filter "*.patch" -File |
    Sort-Object LastWriteTime |
    Select-Object -Last 1

if (-not $latestPatch) {
    Write-Error "[AWX][desktop] evidence_needed: PatchDrop patch / verify with Get-ChildItem $PatchDrop -Filter *.patch"
    exit 1
}

Get-Content $latestPatch.FullName |
    Select-String -Pattern "sk-|AIza|gsk_|pcsk_|sb_(?:secret|publishable)_|sbp_[A-Za-z0-9_-]{10,}|password|secret|Authorization|Bearer " -CaseSensitive

git apply --check $latestPatch.FullName
git apply $latestPatch.FullName

Pop-Location
```

Desktop 적용 후:

```powershell
$Applied = Join-Path $PatchDrop "applied"
New-Item -ItemType Directory -Force -Path $Applied | Out-Null
Move-Item -Path $latestPatch.FullName -Destination $Applied
```

규칙:

- secret scan hit가 있으면 적용하지 않고 `secret-leak-risk`로 보고한다.
- `git apply --check` 실패 시 적용하지 않고 원인 파일/라인을 보고한다.
- 적용 완료 후 Desktop에서 검증이 끝나기 전까지 patch를 `applied`로 옮기지 않는다.
- PatchDrop에 남은 `.patch`가 있으면 추가 소스 편집보다 PatchDrop 검토를 우선한다.

## 4. Gradle 캐시와 빌드 출력 분리

Desktop:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null
```

Mac mini:

```bash
export AWX_AGENT_HOST=macmini
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=macmini
export GRADLE_USER_HOME="$HOME/.gradle-awx-macmini"
export PROJECT_CACHE="$HOME/.awx-gradle-project-cache/macmini"
mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE"
```

공유 금지:

```text
.gradle\
build\
build-*\
.next\
.next-*\
node_modules\
.turbo\
.swc\
```

## 5. bootRun 포트 분리

Desktop 기본 포트:

- app: `8080`
- management: `8081`

Mac mini 검증 포트:

- `18160`-`18199`

Desktop boot/smoke 전 확인:

```powershell
function Test-PortInUse([int]$Port) {
    $hit = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
    if ($hit) {
        $hit | Select-Object LocalAddress,LocalPort,State,OwningProcess
        return $true
    }
    return $false
}

if ((Test-PortInUse 8080) -or (Test-PortInUse 8081)) {
    Write-Error "[AWX][desktop] port-conflict"
    exit 1
}
```

규칙:

- 동일 host/cache/source에서 병렬 `bootRun` 금지.
- `scripts\verify_control_plane_topology.ps1`를 표준 topology wrapper로 사용한다.
- smoke는 순차 실행한다.

## 6. SourceSet Intake

소스 편집 전:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-ChildItem -Recurse -File -Depth 4 |
  Where-Object { $_.Name -in @("settings.gradle","settings.gradle.kts","build.gradle","build.gradle.kts","gradlew","gradlew.bat","verify_boot.ps1","verify_boot.sh","verify_boot_plus.sh") } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Get-ChildItem -Recurse -Directory -Depth 6 |
  Where-Object { $_.FullName -match "(src\\main\\java_clean|src\\main\\java|main\\java|project\\src\\main\\java)$" } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Select-String -Path .\settings.gradle,.\build.gradle.kts,.\app\build.gradle.kts `
  -Pattern "sourceSets|srcDirs|java_clean|main/java|app/src|includeLegacyModules|langchain4j" `
  -ErrorAction SilentlyContinue

Pop-Location
```

판단:

- root active backend: `main/java`, `main/resources`.
- `:app` active owner: `app/src/main/java_clean`, `app/src/main/resources`.
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives, backups, generated output은 Gradle evidence가 없으면 inactive/reference.

## 7. Desktop 검증 명령

Prompt-only 패치면 manifest/file/hash 검증으로 충분하다. Java/resources 변경이면 아래 순서로 넓힌다.

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null

.\gradlew.bat projects --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir "$ProjectCache"
```

YAML scan은 duplicate key와 syntax를 모두 확인한다. Secret scan은 count만 출력하고 값을 출력하지 않는다.

```powershell
$hits = Select-String -Path ".\main\java\**\*.java",".\main\resources\**\*.yml",".\main\resources\**\*.yaml" `
    -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" `
    -Recurse -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop][security] secretPatternHits=$($hits.Count)"
```

## 8. 실패 분류자

SMB/coordination:

- `index-lock-conflict`: `.git\index.lock` 존재.
- `worktree-overlap`: Mac mini 활성 worktree가 target file 작업과 겹침.
- `patch-drop-pending`: PatchDrop에 미적용 `.patch` 존재.
- `branch-ownership-mismatch`: 현재 branch가 `agent/macmini/*`.
- `smb-conflict-risk`: Mac mini가 원본 SMB 경로를 직접 수정 중인 것으로 의심됨.
- `port-conflict`: `8080`/`8081` 또는 합의된 smoke 포트 충돌.
- `gradle-cache-collision`: Gradle user/project cache 미분리.

Build/source:

- `wrong-sourceset`
- `langchain4j-version-purity`
- `yaml-parse`
- `duplicate-class-fqcn`
- `cannot-find-symbol`
- `missing-task`
- `gradle-distribution-network-cache`
- `spring-bean`
- `spring-bind`
- `placeholder`
- `secret-leak-risk`
- `other`

분류 후 처리:

- 충돌 분류는 source edit 중단이 기본값이다.
- 환경/cache 분류는 분리 설정 후 한 번만 재시도한다.
- source/build 분류는 evidence와 최소 patch 후보를 분리해 보고한다.

## 9. Mac mini 제출물 형식

Mac mini는 PatchDrop에 아래 세트를 둔다.

```text
<topic>.patch
<topic>.verification.log
<topic>.report.md
```

Mac mini report:

```markdown
## Mac mini Patch Producer Report
- topic:
- branch:
- worktree:
- changed files:
- investigation commands:
- verification commands:
- observed result:
- failure classification:
- secrets touched: no
- Desktop apply command:
- evidence_needed:
```

## 10. Desktop 최종 보고 형식

```markdown
## 요약
- 2~5줄. 실제 수정 범위, SMB 충돌 여부, 검증 상태만.

## do01 / Observation
- 실행한 PowerShell 명령.
- 핵심 로그 최대 10줄.
- Git root / branch / worktree 상태.
- active sourceSets.
- SMB/PatchDrop/index.lock 상태.
- Mac mini 활성 worktree 여부.
- missing evidence는 `evidence_needed`로 명시.

## do02 / Patch Blocks
- 파일별 Observation.
- Before snippet.
- After snippet.
- Minimal unified diff.
- 이 파일만 수정한 이유.
- Checkpoint log 추가 여부.
- Secret masking 방법.
- Rollback 방법.

## do03 / Setup Commands
- repo root에서 실행할 정확한 PowerShell 명령.
- Desktop cache isolation 포함.
- network/cache 의존 명령과 source 명령 분리.
- 외부 API key 필요 명령 분리.

## do04 / Verification
- Command.
- Expected success condition.
- Observed result.
- Failure classification.
- Retry decision.
- Remaining `evidence_needed`.

## do05 / Risks & Next Steps
- SMB 충돌 위험: H/M/L.
- 다음 단일 우선 patch.
- Mac mini에 알려야 할 PatchDrop 메시지.
- confidence: L/M/H.
```

절대 말하지 말 것:

- Desktop 명령 출력 없이 build passed.
- boot command 없이 boot passed.
- 실제 non-secret credential path와 response 없이 provider works.
- Mac mini 검증 결과만으로 Desktop final proof.
