# 장치별 공유 작업 배정과 인수

공유 소스와 기존 SMB 연결은 그대로 사용한다. `scripts/awx_device_work.py`가 공유 작업 상태를 관리하고, `scripts/awx_device_policy.py`가 장비의 현재 자원과 완료 기록을 비교한다. 실제 코드 편집은 기존 source-edit/Notebook guard, 빌드와 런타임은 기존 `awx_host_runtime.py`를 사용한다.

이 도구는 현재 호출에서 작업을 배정하고 인수하는 기능이다. 공유 폴더에 명령을 넣으면 실행하는 셸이나 상시 스케줄러를 설치하지 않는다. 연결된 각 장비의 에이전트가 `next`로 작업을 인수하고, 원래 승인된 작업을 실행한 뒤 완료를 기록한다. 큐의 `succeeded`는 호출자가 관측해서 제출한 결과이며 원격 실행/사용자 의미 충족을 독립적으로 인증하지 않는다.

## 기본 역할과 자동 조정

| 장비 | 기본 선호 작업 | 배정 조건 |
|---|---|---|
| Desktop | 빌드·테스트·통합·RAG·오케스트레이션 | 최신 CPU/메모리/부하, 필요한 OS·GPU; 최종 통합은 Desktop |
| Notebook | 지시서·설계·조사·가벼운 수정 | 독립 작업과 가벼운 검증 가능; 직접 소스 편집은 Y-drive guard |
| Mac mini | ARM 네이티브, 보조 서버·검증·임베딩·전처리·자동화 후보 | 자동 전환은 비교 실측 필요; ARM 필수 작업 또는 명시적 수동 선택 가능 |

역할은 우선순위이고 CPU·GPU·메모리·OS 요구 조건은 필터다. 5분보다 오래된 상태, 미관측 CPU 부하/가용 메모리, 부족한 자원, 95% 이상의 부하는 배정에서 제외한다. NVIDIA 상태를 관측했을 때만 CUDA 자원을 보고한다. Apple Silicon이라고 GPU 모델 실행이나 Metal 가용성을 추정하지 않는다. ARM CPU 작업은 별도로 선택할 수 있다.

최대 동시 인수는 기본 Desktop 2개, Notebook/Mac mini 1개다. 장비별 빌드·테스트·통합·RAG·오케스트레이션은 동일 `heavy` 자원을 예약한다. 명시적 `resources`는 같은 출력/서비스를 공유하는 작업만 직렬화한다. 서로 다른 장비/파일/자원 작업은 동시에 진행한다.

완료 기록 중 종류·요구 조건·`workloadKey`가 같은 성공 기록을 사용한다. 최근 30일, 최대 30개, 장비당 최소 3개의 중앙값을 비교한다. Mac 자동 배정은 Desktop보다 전달시간을 포함한 중앙값이 최소 10% 짧을 때 허용한다. 수동 배정도 하드웨어·OS·소스 권한 조건을 우회하지 않는다. 이것은 경과시간 비교이며 전력 효율 측정은 아니다.

`workloadKey`는 같은 명령·입력 규모·검증 기준·소스 버전의 비교 단위를 나타내야 한다. 다른 규모나 코드 버전의 작업을 같은 키로 합치지 않는다. 합성 테스트 기록을 실제 공유 큐에 등록하지 않는다.

## 공유 상태

```text
data/agent-handoff/device-work/
  devices/<device-id>/snapshot.json
  tasks/<task-id>/spec.json        # 고정 요구 조건과 입력/문맥 해시
  tasks/<task-id>/targets.json     # 기존 source-edit manifest 형식
  tasks/<task-id>/state.json       # 담당 장비, 시작/종료, 변경 파일, 처리시간
  tasks/<task-id>/artifacts/       # 지시서와 분석 결과
  reservations/<key-hash>.json    # 작업 소유의 장비 슬롯/자원 예약
```

각 task 디렉터리의 짧은 `.operation` 생성이 상태 갱신을 보호한다. 소스 전체를 잠그지 않는다. 같은 작업 ID와 같은 명세 재등록은 기존 상태를 반환하며, 다른 명세로 같은 ID를 재사용하면 충돌이다. 완료/실패/취소된 ID는 다시 인수하지 않는다. 재시도가 필요하면 실패 원인을 해결한 뒤 새 ID로 명시적으로 등록한다.

연결 단절/강제 종료로 남은 claim은 자동 재할당하지 않는다. `needsReconciliation=true`나 `operationInterrupted=true`는 기존 실행자·산출물·예약의 확인이 필요하다는 뜻이다. 시간 경과만으로 파일을 삭제하지 않는다. 정상 소유자는 실제 작업이 멈춘 것을 확인하고 `failed` 또는 `cancelled`로 종료할 수 있다. 이미 terminal 상태라면 같은 완료 호출로 자신에게 남은 자원 예약 정리를 재개할 수 있다.

## 공통 호출

저장소 루트에서 UTF-8 JSON을 stdin 또는 `--input-json <정확한 JSON 파일>`로 전달한다. Desktop은 `C:\AbandonWare\demo-1\demo-1\src`, Notebook은 검증한 `Y:\`에서 실행한다. macOS는 명시적으로 선택한 저장소 경로에서 `python3`를 사용한다.

```powershell
'{"action":"probe","role":"desktop"}' | python -B scripts/awx_device_work.py
'{"action":"status"}' | python -B scripts/awx_device_work.py
```

Control Tower에 연결한 동일 진입점은 `python -B scripts/awx_mcp_toolbox.py device_work` 또는 MCP `device_work`다. 기존 `shared-read` MCP 연결은 변경 도구를 계속 차단한다. Notebook의 승인된 지시서/큐 작업은 해당 장비에서 로컬 CLI로 실행한다. MCP 설정·권한을 자동 변경하지 않는다.

공용 설정, 개인 설정, 인증, 비밀값, 서비스 가용성 레지스트리는 이 큐의 대상이 아니다. 기존 프로젝트 자원/이벤트 도구가 설치된 환경에서는 그 소유권을 유지한다. 이 큐는 작업 인수/완료와 하드웨어 부하/시간 배정을 담당한다.

## Notebook 지시서 → Desktop 실행

Notebook에서 다음 작업을 등록한다. 실제 소스/요청 범위에 맞는 `targets`, `context`, ID를 사용한다. 예제의 `main/java/com/example/lms/prompt/PromptBuilder.java`는 수정 지시가 아니라 읽기 입력 예시다.

```json
{
  "action": "enqueue",
  "task": {
    "taskId": "proposal-example-01",
    "kind": "directive",
    "workloadKey": "proposal-example-v1",
    "targets": ["main/java/com/example/lms/prompt/PromptBuilder.java"],
    "outputs": ["directive.md", "analysis.json"],
    "requirements": {"cpuCount": 2, "memoryMb": 1024},
    "timeoutSeconds": 1800
  }
}
```

```powershell
'{"action":"probe","role":"notebook"}' | python -B scripts/awx_device_work.py
'{"action":"next","role":"notebook"}' | python -B scripts/awx_device_work.py
'{"action":"start","role":"notebook","taskId":"proposal-example-01"}' | python -B scripts/awx_device_work.py
```

`next`는 최신 장비 상태를 게시하고 자동 배정 가능한 미완료 작업 한 개를 인수한다. 특정 작업을 수동 선택하려면 `claim`에 `taskId`를 준다. 인수 결과의 `outputs[].path`에 산출물을 작성한다. 작업 결과와 지시서 내용은 공유 파일에만 두고 큐에는 경로/해시를 남긴다.

```powershell
'{"action":"complete","role":"notebook","taskId":"proposal-example-01","outcome":"succeeded"}' | python -B scripts/awx_device_work.py
```

필수 산출물이 없거나 읽기 입력/문맥이 변경됐으면 성공을 실패로 분류한다. 다음 Desktop 작업을 새 ID로 등록하고 `dependsOn:["proposal-example-01"]`, `context:["data/agent-handoff/device-work/tasks/proposal-example-01/artifacts/directive.md"]`를 연결한다. Desktop은 같은 `next → start → 실행 → complete` 흐름으로 이어받는다. 의존 작업이 성공하기 전에는 인수할 수 없다.

## 가벼운 코드 수정

`kind:"light-edit"`의 `targets`는 정확한 변경 파일만 지정한다. 큐가 반환한 `targetManifest`를 기존 source-edit 준비/검증 단계에 사용한다. Desktop 예시:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action begin -Root . -Topic <고유-topic> -OwnerId <현재-소유자> -TargetManifest <인수결과-targetManifest>
```

반환된 실제 `leaseName`에 해당하는 `__patch_drop__/source-edit-locks/<leaseName>/lease.json`을 `start`의 `lease`로 전달한다. 시작 시 같은 루트·타깃·preimage와 기존 source guard를 검증한다. 실제 수정 직전에도 기존 workflow가 요구하는 Verify를 적용한다. 큐를 인수했다고 소스 lease가 생기지는 않는다.

Notebook은 기존 `macsrc_smb_patch_guard.ps1 -Mode Prepare/Verify`의 Y-drive identity·원본·경계·nonce 규칙을 그대로 사용한다. 원래 owner 값은 현재 프로세스의 `AWX_SOURCE_EDIT_OWNER`에만 제공하여 start가 기존 Verify를 호출하게 한다. 큐/리포트에는 저장하지 않는다. Notebook 큐 메타데이터 변경도 저장소가 고정한 SMB identity 검증을 통과해야 한다.

코드 수정과 집중 검증 후 큐 완료를 기록하고 원래 Desktop End 또는 Notebook Complete/Abort 절차로 lease를 정리한다. 큐 완료는 원래 소스 세션의 최종 검증을 대체하지 않는다. 삭제·원상복구·미선언 파일의 의미 검증은 기존 guard에 남는다. Mac 공유 소스 직접 수정은 배정하지 않으며 기존 독립 작업본/PatchDrop 역할을 유지한다.

## 빌드·RAG·ARM 작업과 시간 기록

명령은 실행자가 승인된 정확한 명령을 선택한다. 큐의 임의 문자열을 실행하지 않는다. Desktop 빌드는 기존 호스트별 출력/캐시 런처를 사용한다.

```powershell
python -B scripts/awx_host_runtime.py run --runtime gradle --root . compileJava
```

런처의 추가 인자 형식은 현재 `awx_host_runtime.py` 계약을 따른다. 실행 시작 직전에 `start`, 실행/검증이 끝나면 실제 결과에 맞춰 `complete`를 호출한다. `transferSeconds`는 명시적으로 관측한 전달시간만 더하며 기본은 0이다. 작업의 시작/종료 시각과 경과시간은 완료 상태에서 자동 계산한다. 원격 장비의 loopback 주소나 예전 성공 기록으로 RAG/모델 호출을 성공이라고 표시하지 않는다.

Mac mini 준비는 `probe`의 `role:"macmini"`로 상태를 게시하는 것으로 시작한다. Apple Silicon 필수 작업은 `requirements:{"system":"Darwin","arch":"arm64"}`로 등록한다. 아직 비교 기록이 없는 일반 보조 작업은 `route`의 `preferredDevice`로 실제 장비 ID를 지정해 자격을 확인한 후 해당 장비에서 수동 `claim`한다. 실측 기록이 쌓이면 `next`가 같은 비교 단위에서 자동 선택한다.

## 검증과 실제 장비 수락

```powershell
$env:PYTHONPATH = Join-Path $PWD 'scripts'
python -B -m unittest scripts.test_awx_device_work scripts.test_awx_device_work_boundaries scripts.test_awx_device_work_integration
```

자동 검증은 임시 디렉터리의 실제 다중 프로세스 경쟁, 입력 변경, 산출물 전달, 상태 보존, 기존 PowerShell source Verify 호출을 포함한다. 물리 장비 검증은 별도다:

1. 실제 Notebook `Y:\`에서 identity 검증 후 probe를 게시하고 Desktop status에서 해당 ID/시각을 확인한다.
2. 같은 artifact-only 작업을 두 장비에서 동시에 claim하여 한 소유자만 남는지 확인한다. 다른 작업은 동시에 완료되는지 확인한다.
3. Notebook 산출물을 Desktop의 다음 작업 context로 연결하고 해시와 완료 상태를 확인한다.
4. Mac mini에서 ARM 네이티브 검증을 실행하고 성공/실패와 경과시간을 기록한다. 동일 비교 단위로 Desktop/Mac 각각 최소 3회 측정하기 전에는 자동 성능 우위 주장을 하지 않는다.

소스가 같다는 사실, 로컬 fixture 성공, 전달 파일 존재만으로 실제 SMB 다중 클라이언트 동작이나 Mac 성능을 인증하지 않는다. 현재 장비 연결/로그가 없으면 `physicalRemoteProof=not_observed`를 유지한다.
