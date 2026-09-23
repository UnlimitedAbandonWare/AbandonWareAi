# Codex 자율 작업과 복구

현재 목표 범위 안의 안전한 작업은 **분석 → 선택 → 수정 → 검증 → 기록 → 다음 단계**로 이어간다.
설계나 다음 단계마다 사용자의 “전부 진행해”를 다시 받지 않는다. 현재 사용자 지시와
소스 소유권, 동시 편집, 비밀정보 보호, 실제 검증 규칙은 계속 적용한다.

## 판단 기준

| 상황 | 기본 행동 |
|---|---|
| 되돌릴 수 있는 코드·테스트·디버깅·로그·리팩터링·문서 변경 | 자율 실행, 선택 이유와 검증 기록 |
| 비용·성능·회귀 위험에 차이가 있는 구현 선택 | 목표를 만족하는 최소 변경을 직접 선택 |
| 실패 원인이 현재 증거로 확인되는 로컬 수정 | 원인 분류, 안전한 복구/수정, 재검증 |
| 동시 작성자, 바뀐 preimage, 미확인 원본 또는 손상된 백업 | 해당 작업만 보류, 독립 작업 계속 |
| 대량 삭제, 복구 불가능한 원본 덮어쓰기, 비밀키/권한 변경 | 구체적인 대상·영향·복구 한계를 먼저 정리한 뒤 승인 |
| 실제/비공개 데이터 외부 전송, 대량·상한 없는 유료 호출, 운영 데이터 변경 | 목적지·데이터 범위·호출/비용 상한을 특정하고 승인 |

이미 승인된 정확한 작업은 반복 승인하지 않는다. 도구 태그, “다 진행해” 또는 낮은
위험 점수만으로 민감한 외부 전송이나 불가역 작업의 승인을 추정하지 않는다.
컴퓨터/브라우저/연구 도구를 사용하지 않아도 로컬 근거로 끝나는 작업은 계속한다.

위험 점수표 작성은 선택 사항이며 일상 작업의 선행 조건이 아니다. 사용할 때 점수는 `5 × (복구 난이도 + 영향 범위 + 회귀 위험 + 불확실성 + 비용)`이며 각 항목은
0~4다. 0은 영향 없음/검증됨, 1은 좁고 쉽게 복구 가능, 2는 관련 경계 검사가 필요,
3은 여러 경계와 상당한 불확실성, 4는 가장 높은 검증 부담을 뜻한다. 측정된 사고 확률이 아니다.
0~24는 집중 검증, 25~59는 영향 경계 검증, 60~100은 변경 분할과 강화 검증을 선택한다.
손실 위험 플래그는 점수보다 우선하며, 필요한 사실을 모르면 `evidence_needed`로 둔다.

## 필요할 때 사용하는 복구 체크포인트

일상적인 되돌릴 수 있는 작업은 필요한 원본 바이트·diff 보존과 집중 검증으로 바로 진행한다.
설계·단계 승인, 세 역할 판정, 형식적인 체크포인트를 매번 요구하지 않는다.
아래 절차는 복구 도구를 사용하기로 선택한 사이클에만 적용한다.

`scripts/codex_work_checkpoint.py`는 로컬 파일 복구 도구다. 패치를 적용하거나 명령을 실행하지 않고,
이미 승인된 작업의 preimage/postimage와 호출자가 실제 관찰한 검증 종료 코드를 연결한다.
이 기록은 인증된 테스트 실행 증명이 아니므로 실제 명령 결과와 함께 판단한다.
소스 파일 및 실행 가능한 도구 파일은 **기존 소스 세션 lease**가 필요하다. 이 도구가
소스 소유권·현재 경계 검사 및 명시적으로 선택한 독립 검토를 대신하지 않는다.

각 사이클의 작업 디렉터리는 고유하게 지정한다. 예:
`data/agent-handoff/codex-autonomy/<task>/<cycle>`.
개인 AGENTS.md에는 `--root C:/Users/nninn/.codex --run autonomy-checkpoints/<task>`를 사용한다.
체크포인트에 원본 바이트와 diff가 있으므로 외부 게시/전송하지 않는다.

`decision.json` 예시(현재 작업에 맞게 판단한 값으로 작성):

```json
{
  "goalId": "current-goal-id",
  "reasonCode": "minimal-verified-fix",
  "risk": {"recovery": 1, "blastRadius": 1, "regression": 2, "uncertainty": 1, "cost": 0},
  "gates": {
    "bulkDelete": false,
    "unrecoverableOverwrite": false,
    "credentialChange": false,
    "externalRealData": false,
    "paidBulkCalls": false,
    "productionMutation": false,
    "permissionChange": false,
    "irreversibleLoss": false
  }
}
```

체크포인트를 사용할 때의 Markdown 작업 명령 형식:

```powershell
$cycle = 'data/agent-handoff/codex-autonomy/my-task/cycle-01'
$decision = 'data/agent-handoff/codex-autonomy/my-task/decision.json'
python scripts/codex_work_checkpoint.py assess --decision $decision
if ($LASTEXITCODE -ne 0) { throw 'decision-not-autonomous' }
python scripts/codex_work_checkpoint.py begin --root . --run $cycle --decision $decision --target docs/example.md
if ($LASTEXITCODE -ne 0) { throw 'checkpoint-not-prepared' }

# 이 지점에서 현재 지침을 따라 선언한 파일에만 최소 패치를 적용한다.
# 소스 작업은 기존 source_edit_session Verify를 패치 직전에 수행한다.

python scripts/codex_work_checkpoint.py seal --root . --run $cycle
if ($LASTEXITCODE -ne 0) { throw 'postimage-not-sealed-reconcile-owner' }
$verifyExit = 1
try {
    # 현재 변경을 검증하는 실제 명령으로 교체한다. 외부 호출이나 소스 재작성은 금지한다.
    python -m unittest scripts.test_codex_work_checkpoint
    $verifyExit = $LASTEXITCODE
} finally {
    python scripts/codex_work_checkpoint.py finish --root . --run $cycle --exit-code $verifyExit --command-id focused-check
    $checkpointExit = $LASTEXITCODE
}
if ($verifyExit -ne 0 -or $checkpointExit -ne 0) {
    # 원인 분류와 복구 상태를 읽고 수정/독립 작업으로 이어간다. 성공으로 표시하지 않는다.
    python scripts/codex_work_checkpoint.py status --root . --run $cycle
}
```

소스/실행 도구를 포함하는 `begin`에는 실제 세션에서 반환된 lease 파일의 상대 경로를
`--lease __patch_drop__/source-edit-locks/<actual-leaseName>/lease.json`로 전달한다.
topic만으로 경로를 추정하지 말고, 기존 helper가 반환한 `leaseName`과 fingerprint를 확인한다.
lease는 현재 root, 소유자, 만료 시각, target-scoped 경로, `mutationAllowed`와 일치해야 한다.
검증 로그가 이미 안전하게 저장돼 있다면 `finish --log <local-log>`로 원인 분류를 보강한다.
원문 로그는 체크포인트에 복사하지 않고 분류별 건수와 해시만 기록한다.

## 상태와 실패 복구

| 상태 | 의미와 다음 행동 |
|---|---|
| `autonomous` | 위험 판단만 완료; 원본/소스 게이트 증명은 별도 |
| `approval_required` | 손실 위험 플래그 있음; 체크포인트 생성/원본 변경 없음 |
| `prepared` | 원본 바이트와 해시 보관; 현재 owner 확인 후 수정 |
| `sealed` | 수정 직후 postimage와 diff 고정; 실제 검증 실행 |
| `verified` | 종료 코드 0, 대상/백업/lease 일치; 다음 목표 단계 진행 |
| `rolled_back` | 검증 실패, 이번 변경 복원 및 해시 일치; 실패 자체는 계속 기록 |
| `hold` | 파일 변경·경로·백업·lease 이상으로 완료/복구 보류; 범위와 다음 확인 기록 |
| `restoring` | 복구 도중 중단 가능; 현재 파일별 pre/postimage와 owner를 먼저 대조 |

### 최종 완료 감지와 자동 정리

최종 리포트와 필수 검증이 모두 끝났으면 같은 cycle 폴더에
`task-cleanup-request.json`을 준비한다. 계약은
[완료 작업 정리](../.agents/skills/demo1-completed-directive-cleanup/references/task-cleanup-contract.md)를 따른다.
`finish`는 성공한 checkpoint에서만 이 파일을 자동 감지한다. goal ID와 sealed
postimage를 대조하고 요청 해시를 고정한 뒤 기존 Windows 정리 엔진을 한 번 호출한다.
요청이 없는 checkpoint는 기존처럼 다음 목표 단계로 진행한다.

패치 작업은 실제 적용 결과와 전체 검증을, 리포트 전용 작업은 최종 리포트 검증을
별도로 확인한다. 완료 후에는 `taskStatus=completed`, `stopWork=true`, `nextAction=none`을
기록한다. 정리 실패 시 checkpoint의 `verified`는 유지하고
`nextAction=cleanup-only-after-condition-change`로 소스 재실행을 막는다.
시간 초과나 읽을 수 없는 응답은 receipt와 증거를 먼저 재조정하며 자동 재시도하지 않는다.

원본 소스, 최종 패치와 리포트, 검증 로그, `before/*.bin`, manifest와 diff는 보존한다.
명시된 임시 파일과 대체된 패치, 바이트가 동일한 중복 리포트만 복구 사본과 삭제 계획을
먼저 기록한 뒤 삭제한다. 폴더 전체 삭제나 다른 작업의 종료는 수행하지 않는다.
`stopWork`는 호출자에게 작업 종료를 알리는 상태이며 Codex UI 보관이나 프로세스 종료
그 자체의 증거가 아니다. 해당 도구와 소유 리소스 해제는 현재 작업의 완료 스킬이 담당한다.

기존 더러운 작업 트리의 **수정 직전 바이트**를 보존한다. Git HEAD로 덮어쓰지 않는다.
검증 실패 시 이번 작업이 만든 새 파일은 sealed 해시가 그대로일 때만 회수한다.
기존 파일은 백업과 postimage가 모두 일치할 때만 복원한다. 모든 대상을 선검사하고
각 파일 쓰기 직전 다시 검사한다. 다중 파일 복구는 원자적 트랜잭션이 아니며,
중간 충돌이 생기면 복원한 개수와 남은 보류 상태를 그대로 남긴다.

다른 작성자의 변경, 손상된 백업, 만료/변경된 lease, 경로 재연결이 있으면 강제 복구하지 않는다.
seal 전 중단, 프로세스 강제 종료, 불완전한 복구는 자동으로 변경 소유자를 추정하지 않는다.
현재 파일별 해시와 기존 owner를 대조한 뒤 허용된 범위만 복구하며 lease를 먼저 해제하지 않는다.
도구는 일반 파일의 바이트를 복구한다. ACL, DB, 실행 중 프로세스, 외부 서비스 상태,
Git 인덱스/브랜치 또는 다른 파일의 부수 효과는 복구 대상이 아니다.

검증 원인은 기존 build-error-miner 패턴을 재사용해 assertion, compile, dependency,
classpath/cache, permission/auth, timeout, runtime unavailable 또는 unknown으로 기록한다.
의미/회귀 성공은 실제 테스트가 증명해야 하며, 문자열 분류만으로 원인을 확정하지 않는다.
외부 일시 오류 재시도는 원래 상한 안에서 최대 한 번이며, 같은 blocker를 반복 조회하지 않는다.

체크포인트를 사용한 각 사이클에는 `manifest.json`, `before/*.bin`, `change.diff`, `checkpoint.json`이 남는다.
기존 소스 session 보고서에는 이 경로와 실제 명령 결과를 연결한다. 다음 사이클은 새 경로를 사용한다.
사용자가 목표를 바꾸지 않았으면 마지막 verified 단계 뒤의 미완료 작업부터 이어간다.
이 지침은 Codex 작업의 기본 판단 방식이며 앱 내부 모든 도구를 강제 통제하는 장치나 백그라운드 감시가 아니다.
