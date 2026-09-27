# GPT Pro용 데스크톱/노트북 상태 탐침 도구

이 도구군은 노트북과 데스크톱의 상태를 한 번에 수집하고,
공유 폴더 기반으로 서로 붙여넣기 가능한 GPT Pro 출력물을 만듭니다.

## 1) 개별 장비 탐침 수집

```powershell
# 로컬 저장만 실행
pwsh .\scripts\device_probe_gpt_tool.ps1 -Role desktop -OutputRoot "$env:USERPROFILE\gpt_device_probe"
pwsh .\scripts\device_probe_gpt_tool.ps1 -Role notebook -OutputRoot "$env:USERPROFILE\gpt_device_probe"

# 공유 폴더 업로드까지 실행
pwsh .\scripts\device_probe_gpt_tool.ps1 -Role desktop -OutputRoot "$env:USERPROFILE\gpt_device_probe" -PublishToShare -ShareRoot "\\SERVER\gpt-share"
pwsh .\scripts\device_probe_gpt_tool.ps1 -Role notebook -OutputRoot "$env:USERPROFILE\gpt_device_probe" -PublishToShare -ShareRoot "\\SERVER\gpt-share"
```

`-PublishToShare`는 `-ShareRoot`가 지정됐을 때만 공유 업로드를 수행합니다.
지정하지 않으면 로컬 경로만 생성합니다.

역할을 지정하지 않으면 `-Role auto`로 동작하며, 호스트명에서 `note`, `notebook`, `laptop` 문자열을 감지해 노트북으로 추정합니다.

실행 후 생성 파일 예시:
- `probe-desktop-raw-<host>-<timestamp>.json` (원본)
- `probe-desktop-gpt-<host>-<timestamp>.json` (GPT Pro용 정제본)
- `latest-desktop-gpt.json` (최신 정제본 포인터)
- `probe-desktop-gpt-ready-<host>-<timestamp>.md` (붙여넣기용 요약)

## 2) 데스크톱-노트북 비교

각 장비에서 탐침 JSON 확보 후 아래처럼 실행합니다.

```powershell
pwsh .\scripts\device_probe_gpt_compare.ps1 `
  -DesktopProbe "$env:USERPROFILE\gpt_device_probe\latest-desktop-gpt.json" `
  -NotebookProbe "$env:USERPROFILE\gpt_device_probe\latest-notebook-gpt.json" `
  -OutputDir "$env:USERPROFILE\gpt_device_probe\compare" `
  -IncludeRaw
```

생성 파일 예시:
- `compare_<timestamp>.csv`
- `compare_<timestamp>.md`
- `compare_<timestamp>.json`

`-IncludeRaw`를 켜면 비교에 사용한 데스크톱/노트북 원본 JSON도 함께 저장됩니다.

비교기는 호스트명이 같거나 `machineFingerprintHash`가 같은 두 입력을 동일한 물리 노드로 판정합니다. 이때 정상 비교표를 만들지 않고 `status=INVALID_SAME_NODE`인 JSON/Markdown을 남긴 뒤 비제로 종료합니다. `desktop`/`notebook` 역할명만 다르게 지정하는 것은 2노드 증거가 아닙니다.

`-StaleMinutes` 이내는 `online`, 그 두 배 이내는 `stale`, 그보다 오래되면 `offline`으로 분류합니다. 시간 계산은 UTC 기준입니다.

## 3) SMB 자동 루프 연동(권장)

두 장비가 같은 공유 폴더를 쓰면 다음 루틴으로 요청/응답/ACK를 주고받을 수 있습니다.

```powershell
# 각 장비에서 상태 업로드
pwsh .\scripts\device_probe_gpt_tool.ps1 -Role desktop  -PublishToShare -ShareRoot "\\SERVER\gpt-share"
pwsh .\scripts\device_probe_gpt_tool.ps1 -Role notebook -PublishToShare -ShareRoot "\\SERVER\gpt-share"

# 데스크톱에서 갭 기반 요청 생성
pwsh .\scripts\device_probe_gpt_compare.ps1 -Mode loop -ShareRoot "\\SERVER\gpt-share" -SelfRole desktop -CreateRequest

# 노트북에서 요청 자동 응답
pwsh .\scripts\device_probe_gpt_compare.ps1 -Mode loop -ShareRoot "\\SERVER\gpt-share" -SelfRole notebook -AutoRespond

# 데스크톱에서 ACK 처리
pwsh .\scripts\device_probe_gpt_compare.ps1 -Mode loop -ShareRoot "\\SERVER\gpt-share" -SelfRole desktop -Acknowledge
```

루프 산출물:
- `nodes\<desktop|notebook>\latest\device-probe.json`
- `dashboard\latest-dashboard.json`
- `dashboard\latest-context-pack.md`
- `dashboard\context-gap-report.md`
- `messages\pending-summary.json`
- `timeline\events.ndjson`

정상 완료된 한 작업의 타임라인은 `PUBLISHED -> DISCOVERED -> PROBED -> REQUESTED -> RESPONDED -> ACKNOWLEDGED -> RESOLVED` 순서를 포함합니다. `pending-summary.json`과 대시보드에는 미해결 request/response/ACK 수가 각각 기록되며, 정상 왕복 뒤에는 모두 `0`이어야 합니다. 같은 `TaskId`로 다시 실행해도 이미 존재하는 메시지와 타임라인 이벤트를 중복 생성하지 않습니다. 쓰는 중 끊긴 JSON은 `quarantine\`으로 격리합니다.

### 전체 파이프라인 한 번에 실행

각 물리 장비에서 같은 `TaskId`와 공유 폴더를 사용해 실행합니다. 기본 동작은 데스크톱에서 request 생성과 ACK, 노트북에서 response 생성입니다. 각 단계는 `collect`, `publish`, `compare`, `loop`, `summary`의 종료 코드와 산출물 경로를 출력하고, 하나라도 실패하면 전체가 비제로 종료됩니다.

```powershell
# 데스크톱
pwsh .\scripts\device_probe_gpt_run_all.ps1 `
  -Role desktop -ShareRoot "\\SERVER\gpt-share" -TaskId "probe-handoff"

# 노트북
pwsh .\scripts\device_probe_gpt_run_all.ps1 `
  -Role notebook -ShareRoot "\\SERVER\gpt-share" -TaskId "probe-handoff"
```

요약은 `~\gpt_device_probe\run-all\device-probe-run-all-summary.json`에 생성됩니다. 공유 경로가 없거나 디렉터리가 아니면 실패 단계가 요약되고 비제로 종료됩니다.

## 4) GPT Pro에 붙여넣기

- 먼저 `probe-*-gpt-*.json` 또는 `latest-*-gpt.json`을 주면 됩니다.
- 비교/루프 요약은 각각 `compare_*.md`, `latest-context-pack.md`를 붙여 넣으시면 됩니다.

## 5) 포함 항목

- OS / CPU / 메모리 / GPU / 디스크
- DNS / 네트워크 인터페이스 / 상위 프로세스
- 포트 수집(기본: SSH, HTTP/HTTPS, DB/캐시/MQ, LLM 기본 포트)
- DB/캐시 서비스 존재 여부 및 `/health` 도달성
- Git 브랜치, 변경량, index.lock, PatchDrop 개수
- 환경변수 항목 수/민감 추정, DB/설정 키 추정

## 6) 주의사항

- 민감 키(`API_KEY`, `TOKEN`, `PASSWORD`, `CONNECTION` 등)는 값 자체 대신 길이/해시 형태로만 보관됩니다.
- 원시 MachineGuid와 MAC 주소는 출력하지 않습니다. 노드 식별에는 단방향 SHA-256 기반 `machineFingerprintHash`/`nodeId`만 사용합니다.
- 수집 파일은 사용자 로컬(`~\gpt_device_probe`)과 공유 폴더(옵션)에서 사용됩니다.

## 7) 회귀 테스트

실제 장비나 SMB를 변경하지 않는 임시 디렉터리 기반 테스트입니다. 동일노드 차단, stale 분류, 요청 왕복, pending 0/0/0, NDJSON, 재실행 불변성, 부분 JSON 격리, 공유경로 실패, run-all 단계를 검증합니다.

```powershell
pwsh -NoProfile -File .\scripts\device_probe_gpt_tests.ps1
```
