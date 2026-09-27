# Three-Node SMB Codex Orchestration
# Desktop + Mac mini + Notebook 3-노드 Safe Patch 지침서

이 프롬프트는 **Windows Desktop**, **Mac mini**, **Notebook** 세 노드가 SMB로 공유 소스를 다룰 때 안전하게 Codex/Agent 작업을 나누기 위한 `demo-1` 전용 지침서다.

---

## 0. 노드 역할 요약표

| 노드 | 역할 | SMB 원본 접근 | PatchDrop | Gradle 빌드 | 포트 대역 |
|------|------|--------------|-----------|------------|----------|
| **Desktop** | Canonical Source Owner / Final Applier | **읽기+쓰기** | 최종 검토자 | `./gradlew.bat` 최종 | 8080 / 8081 |
| **Mac mini** | Read-Only Investigator / Patch Producer | **읽기 전용** | 제출자 | worktree 전용 로컬 | 18160–18199 |
| **Notebook** | Secondary Investigator / Conditional Direct Worker | `SMB_ACCESS` read/tools on `Y:\` | Explicit producer handoff only | `LOCAL_PRODUCER` only when isolation selected | 18200–18249 |

---

## 0.1 Notebook SMB mode contract

```text
canonicalWorkspace=Y:\
publicIdentityFields=[canonicalWorkspace,backingShareIdentityVerified,backingShareIdentityReason]
backingShareIdentityVerified=true|false
backingShareIdentityReason=match|mismatch|evidence-needed
readMode=SMB_ACCESS
defaultNotebookMode=SMB_ACCESS
defaultDirectSmbEdit=false
directMode=YDRIVE_SMB_GUARDED_DIRECT
guardedDirectMode=YDRIVE_SMB_GUARDED_DIRECT
directGateExplicitNotebookImplementation=required
requiredGuardSkill=demo1-macsrc-smb-direct-patch
producerMode=LOCAL_PRODUCER
sharedLeaseRequired=true
preimageCompareAndSwapRequired=true
desktopFinalProof=evidence_needed
canonicalQueryCount=3
```

| Mode | Select when | Result |
|------|-------------|--------|
| `SMB_ACCESS` | Read, search, audit, build, tool, or evidence work | `sourceWriteRoot=null`; `authorizedMutation=false`; source remains unchanged. |
| `YDRIVE_SMB_GUARDED_DIRECT` | A Notebook user explicitly requests implementation and every direct-edit gate passes | `sourceWriteRoot=Y:\`; only after backing identity match, declared targets, active boundary, repository guard, absent index-lock, shared lease, immediate preimage verification, focused verification, postimage hashes, rollback, and redaction. |
| `LOCAL_PRODUCER` | The user explicitly selects isolation or direct mode is inapplicable | Work in a Notebook-local clone/worktree and submit the PatchDrop producer bundle; never use this as an automatic fallback from identity failure. |
| `HOLD` | Root identity, explicit authorization, targets, guard evidence, or verification evidence is missing | No write root and no fallback; request the one smallest decision-changing proof. |

Matching backing identity is necessary but not sufficient for mutation. `backingShareIdentityVerified=false or backingShareIdentityReason=mismatch|evidence-needed -> HOLD`; public identity output contains exactly the fields named by `publicIdentityFields`. Desktop remains the canonical owner and final verifier. Mac mini remains a producer. The guarded direct mode does not create a second SMB protocol or replace Desktop final proof.

Desktop exclusively owns its local final-verification checkout.
YDRIVE_SMB_GUARDED_DIRECT is the sole Notebook canonical Y-drive write exception after every gate passes.
PatchDrop-only producer handoff applies to LOCAL_PRODUCER and Mac mini, not YDRIVE_SMB_GUARDED_DIRECT.

---

## 1. 절대 원칙

- Desktop exclusively owns its local final-verification checkout at `C:\AbandonWare\demo-1\demo-1\src`; this does not revoke the gated Notebook exception on canonical `Y:\`.
- Mac mini는 원본 SMB 경로를 직접 수정하지 않고 producer 경로를 유지한다. Notebook은 `SMB_ACCESS`에서 읽기/도구만 수행하며, 명시적 구현 요청과 검증된 backing identity, declared targets, active boundary, repository guard evidence가 모두 있을 때만 `YDRIVE_SMB_GUARDED_DIRECT`를 선택한다.
- Mac mini의 소스 편집은 **별도 worktree** + `agent/macmini/<topic>` 브랜치에서만 한다. Notebook의 `LOCAL_PRODUCER` 소스 편집도 **별도 worktree** + `agent/notebook/<topic>` 브랜치에서만 한다; guarded direct는 위의 완전한 gate를 충족할 때만 `Y:\`에서 허용된다.
- Mac mini and Notebook `LOCAL_PRODUCER` results are submitted as `.patch`, unified diff, logs, and reports through **PatchDrop**. Guarded direct work stays on the repository-owned lease/CAS path and is not converted into PatchDrop.
- Desktop은 PatchDrop 내용을 검토한 뒤에만 canonical root에 최종 적용한다.
- SMB 공유는 기본적으로 **evidence / patch 교환 채널**이다. 위의 guarded Notebook exception은 repository-owned guard로만 처리한다.
- 세 노드가 동시에 같은 파일을 편집할 수 없다. 동시 편집 의심 시 `smb-conflict-risk`로 분류하고 중단한다.
- 최종 proof는 Desktop의 실제 명령 출력만 인정한다.
- LangChain4j 버전은 반드시 `1.0.1`을 유지한다.
- raw API key, client secret, Authorization header, private env 값을 절대 출력하지 않는다.

---

## 2. SMB 경로와 소유 경계

```text
[Desktop] canonical root (읽기+쓰기):
  C:\AbandonWare\demo-1\demo-1\src

[Desktop] PatchDrop:
  C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\

[Mac mini] producer source:
  producer-local clone/worktree only

[Notebook] canonical SMB workspace:
  Y:\
  SMB_ACCESS: sourceWriteRoot=null, authorizedMutation=false
  YDRIVE_SMB_GUARDED_DIRECT: sourceWriteRoot=Y:\ only after the complete direct-edit gate

[PatchDrop exchange]:
  use the declared PatchDrop root only in explicitly selected producer mode
```

> Notebook source writes never infer an alternate mapped drive or raw network root. `Y:\` is the only canonical Notebook workspace in this policy.

---

## 3. 역할 상세

### Desktop

- `C:\AbandonWare\demo-1\demo-1\src` 최종 소스 소유자.
- PatchDrop diff/log 검토자, secret scan 및 `git apply --check` 실행자.
- accepted patch 최종 적용자.
- Gradle/YAML/sourceSet/boot-smoke 최종 검증자.
- 최종 보고서 작성자.

### Mac mini

- Desktop canonical root를 **읽기 전용 evidence**로만 사용한다.
- 별도 worktree: `agent/macmini/<topic>`
- 조사 결과, `.patch`, unified diff, 검증 로그만 PatchDrop에 제출한다.
- Desktop canonical root, `.gradle`, `build`, `main/java`, `main/resources`를 SMB로 직접 수정하지 않는다.
- Mac mini가 만든 빌드/검증 로그는 supporting evidence이며 Desktop final proof가 아니다.
- **포트**: `18160`–`18199`

### Notebook

- `SMB_ACCESS`에서는 `Y:\`를 읽기/도구/evidence 용도로만 사용하며 `sourceWriteRoot=null`, `authorizedMutation=false`다.
- `LOCAL_PRODUCER`는 사용자가 isolation을 선택하거나 direct mode가 inapplicable일 때에만 별도 Git clone/local worktree에서 사용한다.
- 별도 worktree: `agent/notebook/<topic>` (로컬 clone 기준)
- `.patch`, `.report.md`, `.verify.log`, `.sha256.txt` 세트를 PatchDrop에 제출한다.
- Direct source edit는 기본 workflow가 아니다. 사용자가 Notebook 구현을 명시하고 backing identity, declared targets, active boundary, repository guard, absent index-lock, shared lease, immediate preimage verification, focused verification, postimage hashes, rollback, redaction을 확인할 때만 `YDRIVE_SMB_GUARDED_DIRECT`를 선택한다.
- **포트**: `18200`–`18249`

---

## 4. Desktop Preflight (편집 전 필수 점검)

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
        $pending | Select-Object Name,Length
        Write-Error "[AWX][desktop] patch-drop-pending"
        exit 1
    }
}

# Mac mini / Notebook worktree 충돌 확인
git worktree list 2>$null | Select-String "agent/macmini|agent/notebook"

# 포트 충돌 확인
function Test-PortInUse([int]$Port) {
    $hit = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
    if ($hit) { $hit | Select-Object LocalAddress,LocalPort,State,OwningProcess; return $true }
    return $false
}
if ((Test-PortInUse 8080) -or (Test-PortInUse 8081)) {
    Write-Error "[AWX][desktop] port-conflict"
    exit 1
}

Pop-Location
```

편집 중단 조건:

- `.git\index.lock` 존재.
- 현재 branch가 `agent/macmini/*` 또는 `agent/notebook/*`.
- PatchDrop 루트에 미적용 `.patch`가 있음.
- Desktop boot/smoke 대상 포트 `8080` 또는 `8081`이 이미 사용 중.
- 세 노드 중 하나라도 같은 `GRADLE_USER_HOME` 또는 project cache를 공유 중.

---

## 5. Gradle 캐시와 빌드 출력 분리

### Desktop (Windows)

```powershell
$env:AWX_AGENT_HOST        = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID     = "desktop"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null
```

### Mac mini (macOS)

```bash
export AWX_AGENT_HOST=macmini
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=macmini
export GRADLE_USER_HOME="$HOME/.gradle-awx-macmini"
export PROJECT_CACHE="$HOME/.awx-gradle-project-cache/macmini"
mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE"
```

### Notebook (Windows / macOS / Linux 선택)

```powershell
# Windows Notebook
$env:AWX_AGENT_HOST        = "notebook"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID     = "notebook"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-notebook"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\notebook"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null
```

```bash
# macOS/Linux Notebook
export AWX_AGENT_HOST=notebook
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=notebook
export GRADLE_USER_HOME="$HOME/.gradle-awx-notebook"
export PROJECT_CACHE="$HOME/.awx-gradle-project-cache/notebook"
mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE"
```

공유 금지 디렉터리:

```text
.gradle\       (각 노드별 독립 GRADLE_USER_HOME)
build\
build-*\
.next\
.next-*\
node_modules\
.turbo\
.swc\
```

---

## 6. PatchDrop 절차

### 6.1 Mac mini / Notebook 제출

권장 경로는 `producer_bundle.ps1` helper를 쓰는 것이다. 이 helper는 producer-local worktree/clone에서만 실행하며, 명시한 pathspec만 `git diff --binary`로 묶고 `.patch`, `.report.md`, `.verify.log`, `.sha256.txt`, `.manifest.json`, top-level pending notice를 생성한다. pathspec에 명시된 untracked/new file은 `new file mode 100644` hunk로 포함한다.

```powershell
# Windows Notebook 또는 Desktop-local producer clone
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\worktrees\agent-notebook-<topic>\__patch_drop__\producer_bundle.ps1" `
  -Topic "<topic>" `
  -Node notebook `
  -SourceRoot "C:\worktrees\agent-notebook-<topic>" `
  -PatchDropRoot "Y:\__patch_drop__" `
  -PathSpec "main\java\path\Changed.java","src\test\java\path\ChangedTest.java"
```

```powershell
# Mac mini producer-local worktree에서 pwsh 사용 시에도 동일 계약 적용
pwsh -NoProfile -File "<producer-local-worktree>/__patch_drop__/producer_bundle.ps1" `
  -Topic "<topic>" `
  -Node macmini `
  -SourceRoot "<producer-local-worktree>" `
  -PatchDropRoot "<declared-patchdrop-root>" `
  -PathSpec "main/java/path/Changed.java","src/test/java/path/ChangedTest.java"
```

If Mac mini does not have `pwsh`, use the Python helper with the same nested v3 bundle contract:

```bash
python3 "<producer-local-worktree>/__patch_drop__/producer_bundle.py" \
  --topic "<topic>" \
  --node macmini \
  --source-root "<producer-local-worktree>" \
  --patchdrop-root "<declared-patchdrop-root>" \
  --pathspec main/java/path/Changed.java src/test/java/path/ChangedTest.java
```

Desktop promotion path:

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_inventory.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_promote_producer_pending.ps1" `
  -Topic "<topic>" `
  -Node macmini
```

`janitor_promote_producer_pending.ps1` accepts `-Node macmini`, `-Node notebook`, or `-Node desktop`. It promotes exactly one nested producer bundle to top-level `<topic>-v3.*` only after SHA and secret-scan gates pass. The promoted manifest still keeps `desktopFinalProof=evidence_needed`; Desktop apply and Gradle verification remain separate.

Producer helpers must reject Mac mini/Notebook source roots that point at shared source paths, including Desktop canonical source and mapped or mounted network roots. The coordinator-proven PatchDrop root is an exchange destination only, never a producer `SourceRoot`. Do not print or persist the backing network root.

```powershell
# Notebook (Windows): explicitly selected producer handoff
cd C:\worktrees\agent-notebook-<topic>
git diff --binary | Set-Content "Y:\__patch_drop__\notebook\<topic>-notebook-v3.patch" -Encoding UTF8
```

제출 세트 (완전한 번들 기준):

```text
<topic>-<node>-v3.patch
<topic>-<node>-v3.report.md
<topic>-<node>-v3.verify.log
<topic>-<node>-v3.sha256.txt
<topic>-<node>-v3.manifest.json
```

Preferred Desktop apply path after top-level promotion:

Temp-only three-node smoke before a live external handoff:

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\three_node_patchdrop_smoke.ps1"
```

The smoke uses fake temp Desktop/Mac mini/Notebook roots, creates producer bundles for Mac mini and Notebook, promotes exactly one Mac mini v3 patch, applies through a `desktop-consumer` lease, and reports `realPatchDropUntouched=true`.

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_inventory.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\source_edit_session.ps1" `
  -Action begin `
  -Role desktop-consumer `
  -Root "." `
  -Topic "global-source-edit" `
  -OwnerId "desktop-codex" `
  -TtlMinutes 180
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_apply_one.ps1" `
  -PatchName "<topic>-v3.patch" `
  -SourceLeaseOwnerId "desktop-codex"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\source_edit_session.ps1" `
  -Action end `
  -Role desktop-consumer `
  -Root "." `
  -Topic "global-source-edit" `
  -OwnerId "desktop-codex"
```

Use `desktop-consumer` for applying an active top-level PatchDrop bundle. Plain `desktop` source-owner sessions stay blocked while a top-level patch is pending.

### 6.2 Desktop 검토 및 적용

수동 Desktop apply 경로는 없다. §6.1의 `janitor_apply_one.ps1` guarded consumer path만 사용한다. 이 helper는 manifest-pinned cumulative v3 bundle의 manifest `activePatch`, SHA sidecars, 단일 active top-level patch, producer isolation, count-only secret scan(`sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}`, `sbp_[A-Za-z0-9_-]{10,}`), source lease, rollback snapshot, and dry-run gate를 확인한다.

`janitor_apply_one.ps1` 성공 후 §7의 Desktop Gradle verification을 완료하기 전에는 bundle을 `applied`로 승격하지 않는다. verification 실패는 rollback/rejected로 분류하며, manifest/hash 불일치 또는 복수 active 후보는 `patch-drop-pending`으로 HOLD한다.

---

## 7. 노드별 검증 명령

### Desktop 최종 검증

```powershell
$env:AWX_AGENT_HOST        = "desktop"
$env:AWX_BUILD_HOST_ID     = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null

# 1단계: source set / projects
.\gradlew.bat projects --no-daemon --project-cache-dir "$ProjectCache"

# 2단계: compile + purity (test 제외)
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --project-cache-dir "$ProjectCache"

# 3단계: app compile
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir "$ProjectCache"

# 4단계: bootJar
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir "$ProjectCache"

# 5단계: 핵심 테스트 (optional, 시간 예산 확인)
.\gradlew.bat test --tests "*Naver*" --tests "*ModelGuard*" --tests "*HybridWebSearch*" --no-daemon --project-cache-dir "$ProjectCache"
```

### Mac mini / Notebook 로컬 검증 (supporting evidence)

```bash
# worktree에서 실행 — GRADLE_USER_HOME은 노드별 독립 경로 사용
./gradlew compileJava -x test \
  --no-daemon \
  --gradle-user-home "$GRADLE_USER_HOME" \
  --project-cache-dir "$PROJECT_CACHE"
```

---

## 8. Notebook mode transition

1. Read, search, audit, build, and evidence work stay in `SMB_ACCESS` with `canonicalWorkspace=Y:\`, `sourceWriteRoot=null`, and `authorizedMutation=false`.
2. A user who selects isolation uses a producer-local clone/worktree on `agent/notebook/<topic>` and submits a complete nested v3 PatchDrop bundle to the declared PatchDrop root.
3. An explicit Notebook implementation may use `YDRIVE_SMB_GUARDED_DIRECT` only after the repository guard confirms identity, targets, active source boundary, index-lock absence, shared lease, immediate preimage verification, focused verification, postimage hashes, rollback, and redaction.
4. Identity mismatch, missing guard evidence, or an inapplicable direct mode yields `HOLD`: no write root, no automatic local-producer fallback, and one smallest decision-changing proof.

> Do not map an alternate drive or expose a raw network root. Desktop's local canonical root remains a Desktop-owned final-verification fact, never a Notebook write destination.

---

## 9. 실패 분류자

### SMB / 조율

| 코드 | 조건 |
|------|------|
| `index-lock-conflict` | `.git\index.lock` 존재 |
| `worktree-overlap` | Mac mini 또는 Notebook 활성 worktree가 target file과 겹침 |
| `patch-drop-pending` | PatchDrop에 미적용 `.patch` 존재 |
| `branch-ownership-mismatch` | 현재 branch가 `agent/macmini/*` 또는 `agent/notebook/*` |
| `smb-conflict-risk` | Mac mini 또는 Notebook이 원본 SMB 경로를 직접 수정 중 의심 |
| `smb-root-identity-changed` | backing identity가 repository baseline과 불일치 |
| `port-conflict` | 8080/8081 또는 노드 smoke 포트 충돌 |
| `gradle-cache-collision` | Gradle user/project cache 미분리 |
| `patchdrop-path-unresolved` | coordinator가 단일 PatchDrop root를 증명하지 못함 |
| `source-lease-conflict` | declared target에 대한 shared lease를 얻지 못함 |

### 빌드/소스

| 코드 | 조건 |
|------|------|
| `wrong-sourceset` | 비활성 sourceSet 수정 |
| `langchain4j-version-purity` | 1.0.1 이외 버전 감지 |
| `yaml-parse` | YAML 파싱 오류 |
| `duplicate-class-fqcn` | 같은 FQCN 중복 |
| `cannot-find-symbol` | 컴파일 심볼 오류 |
| `spring-bean` / `spring-bind` | Bean wiring / 속성 바인딩 오류 |
| `secret-leak-risk` | 패치에 secret 패턴 감지 |
| `placeholder` | 미치환 `${...}` 잔존 |

분류 후 처리:

- 충돌 분류 → source edit 즉시 중단.
- 환경/cache 분류 → 분리 설정 후 한 번만 재시도.
- source/build 분류 → evidence + 최소 patch 후보 분리 보고.

---

## 10. 노드별 제출물 형식

### Mac mini / Notebook 제출 보고서

```markdown
## Patch Producer Report
- node: macmini | notebook
- canonicalWorkspace: Y:\
- backingShareIdentityVerified: true | false
- backingShareIdentityReason: match | mismatch | evidence-needed
- topic:
- branch:
- worktree:
- changed_files:
- investigation_commands:
- verification_commands:
- observed_result:
- failure_classification:
- secrets_touched: no
- desktop_apply_command:
- evidence_needed:
```

### Desktop 최종 보고서

```markdown
## 요약
- 2~5줄. 실제 수정 범위, SMB 충돌 여부, canonical identity decision, 검증 결과만.

## do01 / Observation
- 실행한 PowerShell 명령과 핵심 로그 최대 10줄.
- Git root / branch / worktree 상태.
- active sourceSets.
- SMB/PatchDrop/index.lock 상태와 exact three-field public identity evidence.
- Mac mini / Notebook 활성 worktree 여부.
- missing evidence는 `evidence_needed`로 명시.

## do02 / Patch Blocks
- 파일별 Observation.
- Before snippet / After snippet / Minimal unified diff.
- 이 파일만 수정한 이유.
- Checkpoint log 추가 여부.
- Secret masking 방법.
- Rollback 방법.

## do03 / Setup Commands
- repo root에서 실행할 정확한 PowerShell 명령.
- Desktop cache isolation 포함.
- network/cache 의존 명령과 source 명령 분리.

## do04 / Verification
- Command / Expected success condition / Observed result.
- Failure classification / Retry decision.
- Remaining `evidence_needed`.

## do05 / Risks & Next Steps
- SMB 충돌 위험: H/M/L.
- canonicalWorkspace, backingShareIdentityVerified, backingShareIdentityReason만 public backing identity evidence로 기록.
- 다음 단일 우선 patch.
- Mac mini / Notebook에 알려야 할 PatchDrop 메시지.
- confidence: L/M/H.
```

절대 말하지 말 것:

- Desktop 명령 출력 없이 "build passed".
- boot command 없이 "boot passed".
- 실제 credential path/response 없이 "provider works".
- Mac mini 또는 Notebook 검증 결과만으로 Desktop final proof.
- backing network root 또는 alternate mapped path를 보고.

---

## 11. SourceSet Intake (편집 전 필수)

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-ChildItem -Recurse -File -Depth 4 |
  Where-Object { $_.Name -in @("settings.gradle","settings.gradle.kts","build.gradle","build.gradle.kts","gradlew","gradlew.bat") } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Get-ChildItem -Recurse -Directory -Depth 6 |
  Where-Object { $_.FullName.Replace([char]92, '/') -match "(src/main/java_clean|src/main/java|main/java)$" } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Select-String -Path .\settings.gradle,.\build.gradle.kts,.\app\build.gradle.kts `
  -Pattern "sourceSets|srcDirs|java_clean|main/java|app/src|includeLegacyModules|langchain4j" `
  -ErrorAction SilentlyContinue

Pop-Location
```

판단 기준:

- root active backend: `main/java`, `main/resources`.
- `:app` active owner: `app/src/main/java_clean`, `app/src/main/resources`.
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives, backups, generated output은 Gradle evidence 없으면 inactive/reference.

---

## 12. 노드 추가/제거 시 체크리스트

노드를 추가할 때:

1. 새 노드용 포트 대역 할당 (겹치지 않게).
2. `AWX_AGENT_HOST` / `AWX_BUILD_HOST_ID` 고유값 지정.
3. `GRADLE_USER_HOME` 독립 경로 생성.
4. worktree 브랜치 네임스페이스 확정: `agent/<node-name>/<topic>`.
5. coordinator가 PatchDrop candidate 하나를 증명했는지 boolean/reason으로 확인.
6. public backing identity evidence가 exact three-field contract인지 확인.

노드를 제거할 때:

1. 해당 노드의 활성 worktree가 없는지 `git worktree list`로 확인.
2. PatchDrop에 해당 노드의 미적용 patch가 없는지 확인.
3. 포트 대역과 Gradle cache 디렉터리를 정리한다.
