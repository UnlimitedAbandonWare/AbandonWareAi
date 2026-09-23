# 파일 범위 락과 스킬 역할 정리 결과

사용자 승인: `설계대로 진행해` (2026-09-14).
목표: 독립 작업의 동시 편집, 충돌 범위만 직렬화, 소유자 종료에 근거한 자동 회수,
진단 가능한 락 상태, 기존 기능을 보존하는 스킬 역할 정리.

## 구현한 동작

| 요구 사항 | 구현과 검증 경계 |
| --- | --- |
| 파일별 동시 수정 | 기존 정규화·preimage 계약 보존. 같은 폴더의 다른 파일과 disjoint PatchDrop은 병행. 같은 파일은 다른 hunk여도 실제 수정 직렬화 |
| 경로 예약 | manifest의 선택적 `reservePaths`가 부모/자식 충돌만 확장. 실제 쓰기는 여전히 개별 `targets` SHA-256으로 제한 |
| 실제 소유자 | lease ID, 작업/소유자/host hash, 선택적 PID·시작시각. 지정 PID는 helper를 감독하는 실제 ancestor여야 하며 unrelated PID와 helper 자신의 PID는 거절 |
| PowerShell 호환 | Windows PowerShell 5와 PowerShell 7의 JSON 시각 표현 차이를 보존. offset·fractional seconds 손실로 살아 있는 PID를 재사용된 PID로 오인하는 회귀 방지 |
| 갱신 | Desktop `heartbeat`, Notebook `Heartbeat`. 별도 sidecar를 immutable lease fingerprint에 연결. Notebook session hash 보존 |
| 자동 회수 | begin/Prepare 및 명시적 recover가 동일한 짧은 등록 handle 아래에서 검사. 로컬 프로세스 종료/PID 재사용이 확인된 정확한 lease만 영수증과 함께 격리 보존 |
| 불확실한 락 | 원격 소유자, 손상된 기록, PID 확인 불가, TTL/디렉터리 나이만 있는 기록은 자동 삭제하지 않음. 알려진 대상의 충돌에만 보류 적용 |
| 진단 | read-only JSON 상태에 파일·작업/소유자 hash·PID·유효 만료·heartbeat·회수 사유. 이벤트 로그에 획득·충돌·갱신·회수·해제 및 대기 시간 |
| 증거 전용 작업 | 생성 Desktop wrapper의 source lease 제거. 기본 증거 폴더를 topic별로 분리하며 명시적 evidence_dir은 유지. 같은 폴더는 OS handle로 조정하고 완료 감사에도 정확한 폴더/topic 전달 |

원격/손상 락을 나이만으로 풀지 않는 이유는 살아 있는 작업을 덮어쓸 수 있기 때문이다.
기존 Notebook 구현에서는 유효한 lease의 폴더 mtime만 오래되어도 삭제되는 결함을
실제로 재현했다. 현재는 그러한 lease와 원본 파일이 보존된다.

`-OwnerProcessId`에는 모든 수정을 감독하는 지속 실행 프로세스만 지정한다.
매 명령마다 종료되는 shell을 쓰는 환경에서는 생존을 추측하지 말고 옵션을 생략한다.
그 경우 owner/fingerprint·heartbeat 기능은 사용할 수 있으나 프로세스 종료에 의한
자동 회수는 evidence-needed로 남는다. 파일 시스템 협력 규약이며 OS 접근 제어는 아니다.

## 스킬 역할과 보존

| 소유 스킬 | 정리 내용 |
| --- | --- |
| scoped-blocker-recovery | 공유 lease 수명/복구/진단 참조와 누락된 실행 메타데이터 추가 |
| demo1-patchdrop-manual-default | `source-edit-locks=0` 전역 조건을 대상 충돌 조건으로 수정. 명시적 PatchDrop 소비 절차 보존 |
| demo1-macsrc-guarded-patch-session | planner 문구를 이미 경로별로 판정하는 실제 실행기에 맞춤. 별도 mutation owner를 만들지 않음 |
| demo1-macsrc-smb-direct-patch | 공통 복구 계약 사용, heartbeat 추가, 중복 실행 예시는 기존 참조로 통합. Y-drive 신원·범위·preimage·Complete/Abort 계약 유지 |
| demo1-debugging-with-two-tools | 상세 AI 관찰 절차를 필요할 때 읽는 참조로 이동. 입력 제한·fallback·검증·반례 규칙 보존 |
| demo1-desktop-canonical-goal-intake | 중복 단계 절차와 정리 상세를 기존 소유 참조로 연결. Stage 2의 독립 RED·새 preflight·lease 재획득 유지 |

스킬의 이름과 주요 진입점은 유지했다. 알고리즘/애플리케이션 Java·resources,
Git index/branch, DB, provider, 배포 경로는 이번 패치 대상이 아니다.
새 스킬군이나 두 번째 소스 변경 프레임워크는 만들지 않았다.

## 검증

검증 로그 및 파일별 전후 hash:
`data/agent-handoff/lock-skill-refactor-20260914/implementation-01/`.
모든 소스 변경 테스트는 임시 저장소에서 수행했다.

| 검사 | 결과 |
| --- | --- |
| 기존 경로/소유권/인덱스/병렬 등록 테스트 | 42 PASS |
| 새 수명·heartbeat·소유 프로세스·격리·상태·안전 해제 테스트 | 13개 시나리오 PASS: 기본 실행 12개 및 추가 PowerShell 7 실행 2개, 중복 1개 |
| Notebook 동시 편집 테스트 | 6 PASS, 내부 Notebook 계약 45 PASS |
| 증거 wrapper 동시 실행·선택한 완료 증거 경로 테스트 | 3 PASS |
| 기존 dispatch 관련 Python 테스트 | 2 PASS |
| 기존 Notebook 스킬/프롬프트 연결 | 4 PASS |
| 변경한 스킬 quick validation·메타데이터 | 6/6 PASS |
| family validator self-test | PASS, 256 assertions, status=verified |
| 확장 completion 감사 테스트 | 60개 중 54 PASS / 6 FAIL. 6건 모두 보존한 수정 전 audit 모듈에서도 동일하게 재현 |

확장 감사의 실패 항목은 DB gap 보고서, goal-next-auto packet, phase2 readiness,
SMB decommission summary, source health scorecard, Supabase read-only snapshot이다.
독립 검토가 보존한 수정 전 audit 모듈을 같은 checkout 산출물에 적용해 6건 모두
동일한 원인으로 실패함을 확인했다. 원인은 오래된 산출물, 누락된 metrics/summary와
기존 외부 증거 계약 차이이며 새 경로/락 코드는 해당 판정 경로에 관여하지 않는다. 개별 비교 결과는 같은 디렉터리의 최종 verification.json에 기록한다.

실제 두 호스트의 SMB 세션, 앱 runtime/browser/provider proof는 `not_observed`다.
이번 변경의 수용 범위는 로컬 helper 계약과 스킬/지시서 산출물이며, 실제 SMB 운용은
기존 Y-drive backing identity gate 아래에서 동일한 명령을 사용한다.

## 운영과 복구

먼저 [공통 수명 계약](../../../.agents/skills/scoped-blocker-recovery/references/lease-lifecycle.md)을 읽는다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action status -Root . -Json
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action status -Root . -TargetManifest <task-targets.json> -Json
```

명시적 `-Action recover`는 begin/Prepare와 같은 증거 조건으로 동작하며,
원본 lease는 `__patch_drop__/source-edit-quarantine/<id>/`에 남는다.
`source-edit-heartbeats`와 `source-edit-events`는 보존한 진단 자료이며 활성 락 목록이 아니다.
`.promotion.lock`과 `.evidence-intake.lock`은 파일 이름이 아니라 열린 OS handle이 락이다.

기존 완료 감사 출력의 `DesktopIntakeSourceLeaseGate` 계열 필드는 역사적 호환 이름이다.
현재 wrapper에서는 source lease 또는 새 artifact coordination 계약의 충족 여부를
나타내며, source lease를 실제로 획득했다는 증거로 사용하지 않는다. 선택한 task/topic과
검증 명령을 함께 확인한다.

복구는 이번 작업의 전후 hash와 소유한 diff만 사용한다. 다른 writer가 postimage를
바꿨으면 원본 백업으로 덮어쓰지 않는다. 작업 중 새로 적용된 체크포인트 규칙으로
recorder가 기존 실행 코드의 패턴을 `secret-pattern`으로 분류해 체크포인트 준비를
거절했다. 선택적 진단 필드 이름 변경은 생략해 호환성을 유지했다. 이후 실제로
재현한 PowerShell 시간 결함은 기존 source-owner Verify와 정확한 byte preimage를
다시 확인해 패치하고, 별도 로컬 preservation/postimage 기록으로 검증했다.
보조 recorder의 HOLD는 해당 기록 작업에만 적용했으며 원래 source gate를 생략하지 않았다. 자동 승인 거절이나 사용자 승인 대기는 아니다.
