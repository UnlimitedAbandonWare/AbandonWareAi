# Desktop 확인과 다음 단계 선택

이 계약은 기존 Meta 작업의 상태 해석만 추가한다. root/lease/preimage/rollback은 기존 owner gate가 맡는다. 사용자 요청 범위 안의 다음 단계를 에이전트가 이어서 수행하며, 별도 상주 프로세스나 예약 작업은 설치하지 않는다.

역할 소유자는 E0/기록 판단=`demo1-meta-display-resume`, E1/E2 구현과 집중 검사=`demo1-meta-display-sync-client`, E3/E4 실제 관측=`demo1-meta-display-verification`이다. 역할별 SKILL.md와 paired metadata도 기존 taskBinding에 포함한다. schema와 selector는 이 위치의 원본 하나만 사용한다.

## 1. 호출과 범위

현재 Desktop의 확인된 `C:\AbandonWare\demo-1\demo-1\src`에서:

```powershell
python .\.agents\skills\demo1-meta-display-webapp\scripts\next_step.py --root .
python .\.agents\skills\demo1-meta-display-webapp\scripts\next_step.py --requirements
```

Notebook에서는 동일 스크립트를 `--root Y:\`로 읽기 전용 실행할 수 있다. 출력은 `observedExecutionOwner=notebook-supporting`이며 Desktop 확인이나 소스 쓰기 권한을 만들지 않는다. CLI는 현재 OS/호스트/C 루트/Git 루트를 직접 비교하고 backing path나 명령 stderr를 출력하지 않는다. `--desktop` 같은 소유권 가장 옵션은 없다.

시작 시 한 번, 관련 패치 검증/근거 기록 뒤 한 번 호출한다. 동일한 blocker가 유지되면 새 파일·검증 증거가 생길 때만 다시 실행한다. 입력은 스킬/작업 문서와 아래 정확한 기록 파일뿐이며 광범위한 `goal_next_auto -EnsureFresh`나 외부 도구를 호출하지 않는다.

## 2. Desktop이 실제 접수할 때

1. 기존 `demo1-desktop-canonical-goal-intake`의 `artifact_only` 절차로 현재 C 루트와 가까운 `AGENTS.md`, 스킬, 작업서를 읽는다.
2. 기존 `resolve_desktop_directive_target.ps1 -DirectivePath AGENTS.md`를 Desktop에서 실제 실행한다. `PASS/match`, 실제 C root/target, withinRoot/exists=true, reparseRisk=false를 확인한다. 이 결과는 routing만 증명한다.
3. 실제 출력의 allowlisted JSON을 `data/agent-handoff/codex/report/meta-display-sync-v1/evidence/` 안에 기록하고 E0 기록에서 참조한다. Notebook이 이 Desktop 출력을 대신 생성하거나 ACK Done을 쓰지 않는다. 이 작업은 source directive이므로 일반 handoff-only ACK로 소스 절차를 대신하지 않는다.
4. 소스를 수정할 때마다 기존 source-owner gate를 새로 통과한다. 접수 E0의 재사용은 source gate의 재사용이 아니다.

## 3. 기록은 실행자가 작성한다

고정 경로: `data/agent-handoff/codex/report/meta-display-sync-v1/E0.json`부터 `E4.json`까지. 시각·디렉터리 열거 순서로 후보를 고르지 않는다. 이 기록은 기존 `codex/report`의 작업별 검증 요약이며 Notebook lightweight handoff를 새 manifest/ACK 프로토콜로 감싸지 않는다.

각 JSON은 `schemaVersion=1`, `taskId=meta-display-sync-v1-20260912`, 정확한 `stage`, `status=pass`, `executionOwner=desktop`, `executionMode=executed`, `performedNow=true`, `taskBinding`, `targetHashes`, `checks`를 가진다. 실제 실행이 끝나지 않았으면 PASS 기록을 만들지 않고 원래 오류 증거를 보존한다. 다른 세션이 실행한 자료는 그 원본 기록을 참조하며, 지금 실행한 것처럼 날짜/수행자를 바꾸지 않는다. observedAt과 Desktop session 식별자는 원본 증거에 남긴다.

- `taskBinding`: 선택기의 현재 출력값. 작업서/스킬/계약 변경을 감지한다.
- `targetHashes`: `--requirements`가 선언한 해당 단계 파일 각각의 현재 SHA-256. 변경 후 검증한 값만 사용한다.
- `checks`: 선언된 검사 이름마다 `kind`, `performedNow=true`, timezone이 있는 ISO8601 `observedAt`, `result=pass`, 명령/접수 검사에는 `exitCode=0`, `evidence={path,sha256}`를 기록한다. `observedAt`은 실제 관측 시각이며 현재 시각으로 갱신해 검사를 대신하지 않는다. `path`는 위 evidence 하위의 repo-relative 파일이다. 원문 명령을 입력받아 실행하는 기능은 없다.
- E0의 `intake-reviewed`는 기존 Desktop resolver JSON을 검사한다. `owner=desktop` 문자열이나 lexical-only 출력만으로 통과시키지 않는다.
- E4의 `official-simulator`는 실제 공식 확장 프로그램을 사용한 관측이다. custom viewport 자료는 kind를 바꿔 기록하지 않는다.

검사는 기록 형식·근거 파일 존재·해시·현재 대상 일치를 확인한다. **이 검사는 로그 내용의 진실성이나 실제 명령 실행을 독립적으로 입증하지 않는다.** Desktop 에이전트가 원본 실행·관측과 일치하는지 확인한 후 기록한다. 합성 테스트 fixture를 실제 report 폴더로 복사하지 않는다.

## 4. 단계와 재검증

| 기록 | 다음 단계로 넘어가기 위한 해당 실행 근거 |
|---|---|
| E0 | 현재 Desktop의 작업 접수와 실제 C routing 확인; application source gate는 별도 |
| E1 | 현재 display-core와 전용 fixture의 실제 client 계약 검사 |
| E2 | 현재 UI/client의 방향키·입력·focus 관측 |
| E3 | 현재 일곱 파일의 Meta shell, 실제 local sync, cookie/session 연속성 |
| E4 | 동일 파일 상태에서 공식 Simulator의 실제 실행 관측 |

선행 기록이 없으면 해당 단계로 돌아간다. 변경된 core는 E1부터, UI는 E2부터, shell/icon은 E3부터 재검증한다. 무관한 저장소 파일 변경은 단계 기록을 무효화하지 않는다. E3의 서버/세션/보안 의존 파일도 검사 대상으로 포함되므로 backend가 바뀌면 local sync를 다시 확인한다. sourceSet 선언은 E0에 묶어 변경 시 접수부터 재검토한다.

E3/E4 실행 관측은 24시간이 지나면 다시 확인한다. 이는 이 작업의 재개 정책이며 Meta 공식 요구사항이 아니다. E0/E1/E2의 파일 기반 증거는 관련 파일/계약이 그대로면 재사용할 수 있다. 5분을 넘는 미래 시각, 시각 누락, timezone 없는 값은 거부한다. 기록된 시각만으로 실제 실행을 증명할 수는 없다. 서버 재시작·세션/설정·기기/확장 버전 변경을 관측했다면 24시간 이내여도 해당 실행 근거를 갱신해야 한다. 선택기는 전체 전이 의존성이나 비공개 환경을 탐색하지 않으며 env 값은 입력/기록하지 않는다.

HOLD는 기록 수리/해당 의존 단계에만 적용한다. 예를 들어 E3의 서버 연결 부족이 E2의 독립 UI 검사를 막거나, E4 확장 부재가 client 검사를 막으면 안 된다. 동일 단계 안에서도 미완료 검사만 수행하되 전체 단계 기록은 모두 충족한 뒤 남긴다.

## 5. 출력과 한도

`status`, `nextStage`, `nextAction`, `reason`, `acceptedStageCount`, `taskBinding`, `observedExecutionOwner`, `receiptConsistencyOnly=true`, `sourceMutationAllowed=false`, `sourceGateRequiredBeforeMutation=true`, `desktopFinalProof=evidence_needed`를 출력한다. 모든 단계 기록이 맞으면 `RECEIPTS_COMPLETE`와 `review-desktop-final-evidence`를 반환한다. provider lineage, 공개 HTTPS, 실기기는 이 판정으로 완료되지 않는다.

receipt 최대 64 KiB, 근거 최대 1 MiB, target 파일당 최대 8 MiB이며 고정 5개 단계와 선언된 target만 읽는다. 선택기에는 파일 읽기 사이에서 확인하는 25초의 협력적 조사 예산이 있고 Git 진단은 5초 timeout이다. 개별 SMB/파일시스템 호출의 hard timeout까지 보장하지는 않는다. 실행자는 도구/프로세스의 30초 실행 한도를 적용하고 지연된 이 작업의 진단만 중단한다. 절대/상위/제어문자 경로, reparse/symlink 경유, 미지정 근거 경로는 거부한다. 원문 오류/로그/질문/응답/cookie는 출력하지 않고 reason/count/hash만 출력한다. 출처 기록에도 secret을 넣지 않는다.

실패 분류: missing/corrupt/oversized receipt, task-binding-stale, target-hash-stale, evidence-hash-stale, verification-unproven, verification-time-unproven, runtime-evidence-stale, unsafe-path, desktop-intake-unproven. 잘못된 입력은 fail-closed이며 파일을 수정하지 않는다. root/canonical 검증은 원래 guard가 소유하고, 선택기는 허용된 다음 patch를 직접 실행하지 않는다.

복구는 원래 실행 증거를 확인해 해당 단계 요약만 다시 작성한다. 이전 파일은 지우거나 성공으로 초기화하지 않는다. 제거가 필요하면 이 skill의 선택기·검사·본 참조와 해당 추가 호출 문구만 복구한다. 실제 실행/guard 근거는 보존한다.
