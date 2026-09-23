# 장치 인식형 공유 작업 라우팅 구현 결과

공유 SMB와 기존 소스 소유권 체계를 유지하면서 장치별 작업 배정·인수·완료 기록을 구현했다. Desktop에서 현재 코드와 실제 CLI/stdio 호출을 검증했다. Notebook/Mac mini의 물리 연결, 실제 SMB 클라이언트 간 경쟁, ARM 성능 우위는 이번 실행에서 관측하지 않았다.

## 적용한 동작

- `scripts/awx_device_policy.py`: 역할과 분리된 장비 ID, CPU/가용 메모리/OS/아키텍처/부하/CUDA 관측, 5분 상태 만료, 요구 조건 필터와 완료시간 비교.
- `scripts/awx_device_work.py`: 작업별 공유 큐, 고정 명세/입력·문맥 해시, 의존 작업, 장비 슬롯·자원 예약, 인수/시작/종료/변경 파일/산출물 해시. `next`가 현재 장비의 배정 가능한 작업 하나를 인수한다.
- 기존 `awx_mcp_toolbox.py`, `awx_mcp_stdio_server.py`, `awx-control-tower-tools.json`: `device_work` 한 도구와 설치 키트의 두 구현 모듈 추가. 이전 26개 도구의 계약 해시를 그대로 유지하는 검사 통과.
- 원래 `source_edit_session.ps1`, Notebook direct guard와 호스트별 runtime/cache 런처를 재사용한다. 애플리케이션 Java 코드, SMB 서비스, 비밀값, 자격 증명, 배포 설정은 이 작업의 변경 대상이 아니다.
- 사용 절차는 `docs/device-work-routing.md`와 기존 Control Tower node playbook에 연결했다. 스케줄러/감시자/임의 명령 실행기를 설치하지 않았다.

## 역할과 실측 정책

Desktop은 빌드·테스트·최종 통합·RAG·오케스트레이션, Notebook은 지시서·설계·조사·가벼운 수정을 기본으로 선호한다. 부하나 요구 자원이 맞지 않으면 다른 적격 장비를 평가한다. 최종 통합은 Desktop, Mac의 공유 소스 직접 수정은 기존 정책대로 제외한다.

Mac mini 자동 배정은 같은 작업 종류·요구 조건·workloadKey의 성공 기록을 장비별 최소 3회 확보하고, 전달시간을 포함한 중앙값이 Desktop보다 최소 10% 짧을 때 허용한다. ARM 필수 작업과 명시적인 수동 선택은 하드웨어·권한 조건을 유지하면서 준비/측정에 사용할 수 있다. 데이터가 없으면 성능 우위를 추정하지 않는다. 에너지 효율이나 GPU 모델 생성은 측정하지 않았다.

## 최종 검증

현재 목표의 최종 통합 명령:

```powershell
$env:PYTHONPATH = Join-Path $PWD 'scripts'
python -B -m unittest scripts.test_awx_device_work scripts.test_awx_device_work_boundaries scripts.test_awx_device_work_integration scripts.test_awx_multi_device scripts.test_awx_mcp_stdio_catalog scripts.test_awx_mcp_stdio_protocol scripts.test_concurrent_source_edit scripts.test_source_lease_lifecycle scripts.test_evidence_dispatch_concurrency
```

결과: **133 tests, 실패 0, 건너뜀 2, exit 0, 161.142초**. 별도 출력의 PowerShell 계약 검사도 **pass=45, fail=0**이다. 건너뛴 환경 의존 테스트는 실제 macOS 검증으로 간주하지 않는다.

검증 내용은 다중 프로세스의 같은 작업 인수 경쟁, 독립 작업 동시 진행, 자원/슬롯 고갈, 완료 ID 재실행 차단, 의존 작업, 잘못된 입력·경로·원본/문맥 변경, 산출물 누락, 실제 Desktop source Verify 호출, stale telemetry, GPU/OS/RAM 요구 조건, 실측 Mac 배정, 역할 변경 시 동일 장비 ID, 설치 키트 실제 실행과 stdio 스키마/취소/프로토콜 경계를 포함한다.

이전 설치 키트 테스트의 고정 `sharedSkillCount=64`가 현재 68개 스킬과 맞지 않아 실패했다. 고정 숫자를 현재 공용 스킬 경로 집합과 manifest의 일치 검사로 바꿨다. 신규 스킬을 만들거나 기존 스킬 내용을 바꾸어 개수를 맞추지 않았다.

## 실제 Desktop 관측

CLI `probe`는 Windows/x86_64, 논리 CPU 24개, 가용 메모리 23089MiB, CPU 부하 약 43.5%, CUDA 가용 메모리 24325MiB, GPU 부하 0%를 해당 시각에 관측했다. 지속적인 가용성이나 모든 GPU의 건강을 보장하는 값이 아니다. 장비 ID는 원래 호스트명을 노출하지 않는 해시 기반 ID다.

`device-routing-acceptance-20260914` 작업으로 실제 도구 manifest를 읽고 `device_work`가 한 번 등록된 것을 검사해 `catalog-check.json` 산출물을 만들었다. 다음 작업이 그 경로와 해시를 받아 내용을 읽고 완료했다. 두 작업 모두 `succeeded`였고, 같은 ID 재등록은 기존 종료 시각을 유지했다. 첫 작업의 기록된 경과시간은 약 0.414초이며 Mac 비교 벤치마크로 사용하지 않는다.

새 실제 stdio 프로세스의 initialize/tools-list는 exit 0, 도구 27개, `device_work` 노출을 확인했다. 이미 켜진 다른 MCP 연결의 재시작이나 새 도구 캐시 갱신까지 수행한 것은 아니다.

## 복구와 소유권

현재 소스 세션은 선언된 파일만 예약했으며, 겹치지 않는 동시 세션을 중단하지 않았다. Git index.lock은 제거하지 않았다. Control Tower 파일을 보유하던 다른 세션의 소유권 충돌이 해소된 뒤 연결 변경을 진행했다.

실패한 수정 시도는 checkpoint의 sealed postimage와 원본 해시가 일치하는 파일만 자동 복원했다. `core-02`에서 1개, `integration-01`에서 변경된 4개, `integration-02`에서 5개 파일을 복원했고 실패 기록은 남겼다. 연결 단계의 Git 작업 보호 조건, 카탈로그 삽입 및 지원하지 않는 스키마 키 문제는 최종 연결 검증과 분리해 기록했다. 최종 테스트 성공으로 이전 실패 기록을 지우지 않았다.

로컬 복구·검증 위치:

```text
data/agent-handoff/codex-autonomy/device-routing-20260914/
  preflight-core.json
  preflight-integration.json
  final-acceptance.log
  final-source-postimages.json
  live-desktop-acceptance.json
  core-*/checkpoint.json
  integration-*/checkpoint.json
  final-01/checkpoint.json
  identity-01/checkpoint.json
```

검사한 구현/테스트 파일 10개의 secret-pattern file hit는 0, 최종 postimage drift도 0이었다. 복구 원본과 전체 diff는 로컬 자료이며 외부로 전송하지 않았다. 커밋·푸시·배포는 수행하지 않았다.

## 관측하지 못한 범위와 다음 장비 검증

`physicalNotebook=not_observed`, `physicalMacmini=not_observed`이다. 실제 Notebook의 `Y:\`에서 `probe` 후 같은 큐를 읽고, 별도 artifact-only 작업을 인수/완료하여 Desktop 수신을 확인하는 것이 다음 검증이다. Mac mini는 실제 ARM 실행과 동일 비교 단위의 처리시간을 수집해야 한다. 이 코드는 그 기록과 배정 절차를 제공하며 이번 Desktop fixture를 실제 장비 성능으로 바꾸어 보고하지 않는다.

공유 레코드는 협력하는 실행자를 조정한다. 임의 편집기의 잠금 무시, SMB 연결 단절 중 전 파일의 동시 가시성, 외부 시스템의 exactly-once 부작용까지 보장하지 않는다. 중단된 작업은 자동 재실행하지 않고 원래 소유자와 산출물을 확인한다.

GLM은 현재 CLI 0.144.1/v2에 대한 기존 전송 HOLD 조건을 유지해 호출하지 않았다. 독립 탐색은 Codex 기본 explorer 한 개로 수행했다. AWX의 일반 pipeline 계획 프로브는 45초 timeout으로 실패했으며 이를 신규 큐/stdio 성공 증거로 사용하지 않았다. 이번 구현에는 외부 게시·데이터베이스·유료 생성·수학/논문 조회가 필요하지 않았다.

설계의 프로토콜 근거는 [Microsoft SMB2 CREATE](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/e8fb45c1-a03d-44ca-b7ae-47385cfd7997)와 [Python 파일 교체 동작](https://docs.python.org/3/library/os.html#os.replace)을 확인했다. 파일별 협력적 생성/교체를 사용하며 분산 트랜잭션으로 주장하지 않는다.
