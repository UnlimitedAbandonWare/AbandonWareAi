# AWX 협업 상태 기반 지연 검증 — Codex/Devin 구현 지시서

작성일: 2026-09-28 · Asia/Seoul
산출물 상태: 설계 및 구현 지시서. 사용자 PC 설치·소스 패치·실행 검증은 수행하지 않았다.
작업 기준 경로: `C:\AbandonWare\demo-1\demo-1\src`를 먼저 확인한다. 과거 사용 경로이며 현재 파일/실행 경로가 최종 기준이다.

## 1. 목적과 완료의 의미

같은 작업 트리를 수정하는 Devin·Codex·다른 에이전트 때문에 중간 코드가 반복 빌드되는 문제를 해결한다. 빌드 실패가 실제로 동시 편집 때문인지는 시간대·writer 상태·소스 변경 증거로 검증한다. 모든 기존 실패를 동시 편집 탓으로 분류하지 않는다.

**빌드의 엄격함을 줄이지 말고, 안정된 입력을 검증하도록 실행 시점을 조정한다.**

- 다른 writer의 관련 편집 중: 무거운 검증을 `DEFERRED`로 기록하고 재개 가능한 요청을 남긴다.
- 자신의 편집 완료: `APPLIED_PENDING_VERIFICATION`으로 턴 종료가 가능하다. 제품/패치의 검증 완료와 동일하지 않다.
- 안정된 검증 대상 확보 후: 설정된 필수 검증을 실제로 실행한다.
- 최종 성공: 명시한 프로필의 모든 필수 단계가 같은 대상에 대해 유효한 성공 증거를 가져야 한다.

금지: busy 플래그 때문에 PASS 생성, 예외 삼키기, `|| true`, 테스트 삭제, `-x test`로 최종 게이트 우회, 기존 verification-before-completion 규칙 삭제. 정적 분석·컴파일·테스트·런타임 확인을 서로 대체하지 않는다.

## 2. 먼저 조사할 기존 연결 지점

아래는 과거 사용 맥락에 따른 **탐침 후보**이며 현재 존재·동작을 보장하는 목록이 아니다.

`AGENTS.md`, `.agents/skills/INDEX.md`, `demo1-work-ledger`, `scripts/work_journal.py`, `scripts/source_edit_session.ps1`, `scripts/agent_work_guard.py`, 기존 lease/checkpoint 저장소, `compile-verify-smoke`, `docs/PROJECT_STATUS.md`, `.codex/hooks.json`, `.codex/config.toml`, Devin의 실제 활성 규칙/훅 파일.

실제 호출되는 Gradle wrapper, settings/build 파일, 서브프로젝트, sourceSets, 검증 명령, 런타임 진입점을 확인한다. 디렉터리명만 보고 `src/main`을 중복 결합하지 않는다. 현재 소스 구조를 보지 않고 `:app` 또는 `compileJava`를 하드코딩하지 않는다.

Codex와 Devin Desktop/CLI의 실제 버전·활성 설정·도구 이름·훅 입력을 확인한다. CLI 문서가 Desktop의 현재 설치본과 똑같이 작동한다고 가정하지 않는다.

기존 journal/lease를 상태의 단일 기준으로 유지한다. 별도 `devin_busy.lock`을 또 만들어 다른 진실을 생성하지 않는다. 부족한 원자성·상태 필드만 기존 조정 계층에 추가한다. 검증 ticket/receipt는 이 상태와 연결된 파생 기록으로 둘 수 있다.

Git 초기화·worktree 전환·브랜치·commit·reset·restore·clean·stash를 임의 수행하지 않는다. 다른 에이전트 변경을 덮어쓰지 않는다. 운영 Java/RAG/프롬프트/API 라우팅은 이번 작업의 재설계 대상이 아니다.

## 3. 최소 구조

```text
공통 AGENTS 규칙 + 공유 SKILL
             ↓
기존 journal / edit lease ← 얇은 Codex·Devin 훅 어댑터
             ↓
검증 요청 저장 / 중복 병합
             ↓
로컬 검증 조정자 1개
             ↓
기존 compile / test / smoke 실행 경로
             ↓
대상 해시·실행 증거를 포함한 검증 receipt
```

처음부터 새 서버·DB 서비스·MCP 서버·대시보드·LLM 판단기를 추가하지 않는다. 기존 로컬 저장소와 실행 진입점을 확장한다. 상태 판단은 결정적인 로컬 코드가 맡는다. LLM은 안정된 실패의 원인 분석에만 필요하다.

단순 파일 변경 감지는 보조 신호다. 단일 flag 존재 여부나 프로세스 이름, CPU 사용률, 채팅의 "완료" 문구를 진실로 사용하지 않는다.

## 4. writer 등록과 검증 배타권

### 4.1 writer는 세션이 아니라 편집 묶음 단위로 등록

한 세션은 탐색·사고·편집·검증을 오간다. 세션이 열려 있다는 이유만으로 영구 writer로 취급하지 않는다. 단, 여러 파일/도구 호출에 걸친 논리적 변경이 끝나기 전에는 편집 묶음을 자동 종료하지 않는다.

등록 필드의 의미:

- `session_id`, `task_id`, `edit_batch_id`, `agent`, `host_id`
- 소유자 프로세스/래퍼의 `pid`, `process_start_identity`, `owner_instance_id`
- 재발급되는 `lease_token`/`fencing_epoch`
- `state`, `heartbeat_at`, `last_source_change_at`
- 실제 편집 경로와 영향받는 build/resource scope

모든 writer는 첫 수정 전에 공통 조정 잠금 안에서 검증 예약 여부를 확인하고 등록한다. 단순히 먼저 확인한 뒤 나중에 별도 flag를 쓰는 구현은 금지한다. 기존 파일 단위 편집 소유권 규칙도 유지한다.

`heartbeat`는 생존 신호일 뿐 소스 변경이 아니다. heartbeat로 source generation이나 quiet timer를 갱신하지 않는다. 자동 heartbeat는 실제 소유자의 생존에 연결한다. 소유자가 죽었는데 heartbeat 프로세스만 살아 lease를 영구 연장하지 않도록 한다.

### 4.2 검증자도 같은 조정 규약에 참여

검증 시작 판단과 build lease 취득은 동일한 원자적 상태 전이에서 수행한다. 검증자만 mutex를 쓰고 writer는 이를 무시하는 구조는 해결책이 아니다.

기본 구현은 대상 빌드 scope의 협조적 편집을 잠시 멈춘 상태에서 검증한다. 검증은 원본을 자동 포맷/수정하지 않는다. build/캐시 출력은 별도 자원으로 관리한다. 검증 프로세스가 종료되기 전에는 다음 검증자에게 동일 출력 자원을 넘기지 않는다.

scope 판정은 직접 수정 경로뿐 아니라 의존 모듈, 공통 빌드 로직, 생성 소스, 공유 출력과 런타임을 포함한다. 의존성 폐포를 확정할 수 없으면 첫 구현은 프로젝트 전체를 대상으로 보수적으로 조정한다. scope별 최적화는 이후에 한다.

### 4.3 계속되는 Devin 요청에서 검증이 굶지 않게 하기

`SessionEnd`만 기다리지 않는다. writer는 논리적으로 일관된 변경 묶음이 끝나는 시점에 `checkpoint`를 발행하고 편집 lease를 양도할 수 있어야 한다.

검증 요청이 일정 시간 누적되면 `validation_intent`를 등록한다. 진행 중인 묶음은 안전한 경계까지 끝내되, 이후 새 편집 묶음의 진입보다 검증 배타권 취득을 우선한다. 소스 중간 저장을 강제로 멈추거나 다른 프로세스를 임의 종료하지 않는다.

요청 병합 시 가장 오래된 `requested_at`을 유지한다. 새 요청이 들어올 때마다 대기 시간을 초기화하면 영구 기아가 된다. writer가 끝나지 않으면 `WAITING_FOR_CHECKPOINT`를 명시한다. 안전성과 무조건적인 진행을 동시에 보장한다고 주장하지 않는다.

## 5. 상태 및 ticket 계약

권장 상태는 기존 저장소의 열거형과 조정하되, 다음 의미를 잃지 않는다.

| 상태 | 의미 |
|---|---|
| `DEFERRED` | 편집/검증 자원 때문에 아직 검증을 시작하지 못함 |
| `QUIESCING` | writer 해제 후 소스 안정 구간 확인 중 |
| `VERIFYING` | 같은 입력을 고정한 채 필수 단계를 실행 중 |
| `VERIFIED_PASS` | 명시된 프로필의 필수 단계 전부 유효한 성공 증거 보유 |
| `FAILED` | 유효한 안정 입력에서 검증 명령/테스트가 실패 |
| `INVALIDATED` | 실행 중 입력/소유권 변화로 결과를 최신 대상에 적용할 수 없음 |
| `ENVIRONMENT_ERROR` / `TIMEOUT` | 실행 환경·제한 시간 문제. 코드 결함으로 단정하지 않음 |
| `BLOCKED_UNKNOWN_OWNER` / `UNKNOWN` | 상태 파손·소유자 불명·증거 부족. PASS 금지 |
| `WAITING_FOR_RUNNER` | 처리할 검증 실행자가 현재 없음 |
| `SUPERSEDED` | 요청 대상이 더 최신 대상으로 대체됨. 원래 대상 검증 완료는 아님 |

요청은 `ticket_id`, `requester`, `task_id`, `profile`, `scope`, `requested_at`, `target_mode`, `target_identity`, `required_stages`, `covered_requests`를 가진다.

`target_mode=latest`는 최신 안정 상태 1건으로 합치되 필수 테스트 집합·관련 요청·최초 대기 시각을 보존한다. 프로필 호환성을 확인하지 않고 무조건 병합하지 않는다. `exact` 요청은 다른 revision의 성공으로 만족시키지 않는다. 원본을 재현할 수 없으면 SUPERSEDED/재확인으로 남긴다.

status/request 명령의 정상 종료는 요청 처리 성공일 수 있지만, 검증 성공은 아니다. 예를 들어 request가 exit 0을 반환해도 `verification_status=DEFERRED`일 수 있다. 상위 래퍼가 이 차이를 반드시 해석해야 한다.

실제 verify 명령은 exit 0을 VERIFIED_PASS에만 사용한다. 별도 코드(예: 10 보류, 11 무효화, 20 검증 실패, 21 환경 실패, 30 상태 불명)를 정의할 수 있으나 이는 **프로젝트 자체 계약**이다. 이 숫자를 Codex/Devin 훅에 그대로 전달하지 않는다. 훅별 공식 출력 규약으로 변환한다.

## 6. 검증 대상의 동일성

검증 기준은 단순 Git HEAD가 아니라 **실제로 빌드에 들어간 입력**이다. 소스뿐 아니라 테스트·리소스·build 설정·wrapper·의존성 잠금·검증 스크립트·선택 명령·필요한 도구 버전 및 환경 식별자를 포함한다. 비밀정보 원문은 로그/receipt에 기록하지 않는다.

배타권을 가진 상태에서 입력 manifest와 edit epoch를 기록한다. 검증 종료 시 동일성을 다시 확인한다. 해시 시작/끝 일치만으로 중간 A→B→A 변경까지 부정할 수는 없으므로, 참여 writer의 edit epoch와 배타권도 확인한다.

비협조 writer나 미포착 편집 경로가 있으면 강한 일관성 보장을 주장하지 않는다. 기본 대응은 해당 결과 INVALIDATED/UNKNOWN, 경로 통합 후 재검증이다. 더 강한 보장이 필요하면 **짧은 편집 중지 구간에서 완전하게 복사한 고정 스냅샷**을 별도 출력 공간에서 검증하는 후속 기능을 검토한다. 편집 중 폴더 복사나 강제 Git worktree 도입은 대안으로 취급하지 않는다.

같은 입력의 반복 검증은 receipt를 참조할 수 있지만, 기록된 프로필·단계·도구/환경·유효기간이 일치해야 한다. 특히 런타임/외부 의존성 상태는 소스가 같아도 바뀔 수 있으므로 영구 PASS 캐시를 적용하지 않는다.

## 7. 실행 스크립트의 구현 계약

아래 이름은 새 공식 API가 아니라 구현할 로컬 인터페이스의 제안이다. 동일 기능이 이미 있으면 기존 명령을 확장한다.

```text
status                 기존 journal/lease/ticket/runner 상태 읽기
writer begin           원자적으로 편집 묶음 예약; token 반환
writer heartbeat       소유자/token을 확인하고 생존 갱신
writer checkpoint      일관된 변경 묶음을 닫고 검증 기회 양도
writer end             해당 소유자의 묶음만 정리; 성공 추정 금지
request                검증 ticket 저장/병합; 상태를 명확히 반환
run-once               실행 가능할 때 대상 하나 처리; 아니면 즉시 보류
watch                  로컬에서 단일 실행자로 ticket 소비
recover                증거를 확인한 제한적 소유권 복구
```

`watch`는 사용자의 PC에서 실제 프로세스로 실행되어야 한다. SKILL.md의 텍스트만으로 실행되지 않는다. 기존 상주 runner/launcher가 있으면 여기에 결합한다. 없는 상주 프로세스가 있는 것처럼 보고하지 않는다. 이 지시서 작성 자체가 watcher의 설치나 실행을 의미하지 않는다.

worker는 재시작 후 미처리 ticket을 복구한다. 이미 완료한 operation ID는 중복 수행하지 않는다. 종료 신호·예외 처리로 자신이 취득한 자원만 해제한다. 진행 중 child가 남았으면 소유권을 성급하게 회수하지 않는다. 전체 Java/Python 프로세스 일괄 종료는 금지한다.

설정으로 둘 초기 제안값(성능 보장/벤치마크가 아님):

```text
cheap_state_poll_seconds = 2
heartbeat_seconds = 10
stale_suspect_seconds = 90
source_quiet_seconds = 15
validation_priority_after_seconds = 120
max_heavy_verifiers = 1
build_timeout_seconds = 900
```

stale 값은 의심/재조정 시작 기준이지 자동 잠금 삭제 시간이나 완료 판정이 아니다. 절전·네트워크 지연·프로세스 시작 ID·호스트 차이를 확인한다. 소유자 불명 시 BLOCKED로 남긴다.

상태 파일은 작은 원자적 갱신만 수행한다. 이미 transactional store가 있으면 그것을 사용한다. JSON temp/replace만으로 다중 필드·다중 writer 전이를 안전하게 만든 것으로 간주하지 않는다. 상태 변경의 상호배제도 필요하다. 네트워크 공유 경로에서는 로컬 파일 잠금의 보장이 같다고 가정하지 않는다.

2초마다 전체 소스를 해시하지 않는다. 작은 상태/변경 신호를 확인하고, 안정 checkpoint와 검증 시작/종료 때 manifest를 계산한다. build/output/cache/log/heartbeat/coordination 디렉터리를 source quiet 감지에서 제외한다. 이벤트 누락을 대비한 제한적 재조정은 허용한다.

## 8. 훅 연결과 무한 재시도 방지

공식 문서의 현재 형식은 Codex와 Devin CLI가 다르다. 공통 판단 코어와 얇은 어댑터를 분리한다. 기존 훅을 전체 덮어쓰지 않는다. 공식 문서의 설치 경로가 있더라도 실제 설치본의 설정 로딩/신뢰 승인을 확인한다.

- **SessionStart:** 미처리 ticket과 소유권 상태 복구. 세션 전체를 곧바로 writer로 등록하지 않는다.
- **편집 직전:** 실제 쓰기 경로에 대해 writer 등록/소유권/검증 배타권 확인. shell·patch·MCP 경로의 차이를 시험한다.
- **빌드 직전:** `request`/`run-once` 또는 기존 wrapper로 통일한다. 다른 writer가 있으면 durable ticket을 남기고 직접 빌드를 차단한다.
- **checkpoint / Stop:** 편집 묶음이 실제로 끝났는지 확인하고 검증 요청을 연결한다. 단순 PostToolUse마다 묶음 종료를 선언하지 않는다.
- **SessionEnd / Interrupt:** 정리/회수 후보 신호일 뿐 모든 writer 종료나 패치 성공 증거가 아니다.

`Stop`에서 외부 writer가 끝날 때까지 테스트를 요구하며 계속 block하지 않는다. 요청 보존·재개 주체·현재 상태가 기록되면 `APPLIED_PENDING_VERIFICATION`으로 해당 턴 종료를 허용한다. runner가 없으면 WAITING_FOR_RUNNER를 보고한다. 무인 재개가 동작한다고 주장하지 않는다.

여러 훅은 중복/동시 실행될 수 있으므로 훅 순서에 안전성을 맡기지 않는다. 실제 build wrapper 안에서도 조정 배타권을 취득한다. 다른 Stop 훅이 무조건 테스트를 요구한다면 새 훅만 추가하지 말고 기존 완료 정책에 DEFERRED 상태를 연결한다.

훅 프로세스의 exit 0은 훅 처리 성공일 수 있다. 프로젝트 검증 PASS와 혼동하지 않는다. 훅 오류가 도구 실행을 반드시 막는다고 가정하지 말고 설치본의 차단 규약을 테스트한다.

실행 경로를 일반 정규식 하나로 완벽히 분류할 수 있다고 주장하지 않는다. 실제 task launcher와 검증 스킬을 우선 통합하고, 미포착 직접 빌드 경로를 수용 시험에 포함한다. 운영체제 수준의 강제 격리로 과장하지 않는다.

## 9. 단계별 검증 및 런타임 보호

편집 중에도 안전하게 고정한 자기 변경 파일의 읽기 전용 문법/형식 검사는 가능하다. 단, 다른 writer의 중간 파일을 훑고 자동 수정하지 않는다. 이 결과는 전체 빌드 통과가 아니다.

검증 프로필은 현재 프로젝트의 실제 명령에서 추출한다. 예를 들면 `compile`, `targeted-tests`, `integration`, `runtime-smoke`의 요구 단계가 서로 다르다. 부분 프로필 성공을 전체 완료로 확대하지 않는다. 프로필에는 실행 명령·작업 디렉터리·입력/출력·제한 시간·필수 단계를 명시한다.

기본적으로 `clean`/cache 삭제/모든 테스트 반복을 하지 않는다. 그렇다고 최종 프로필의 필수 테스트를 없애지도 않는다. Gradle의 기존 증분/캐시 동작을 보존하고 변경 영향과 검증 목적에 맞게 실행한다.

build 결과와 runtime 결과를 연결한다. `/health` 200만으로 방금 수정한 소스가 실행 중이라고 하지 않는다. source identity → artifact digest → 시작 프로세스/포트 → smoke 결과를 연결한다. 이 연결이 없으면 런타임은 NOT_VERIFIED다.

기존 `source / built / running / onGlasses` 증거 구분, Start/Close 진입점, wear runtime 보호를 유지한다. 안정 빌드 전 운영 런타임 자동 재시작을 하지 않는다. 별도 승인 없이 API 과금 요청·GPU 모델 로딩·안경 프로세스 종료를 추가하지 않는다. 먼저 독립 프로필·mock 기반 검증을 사용한다.

## 10. 실패 처리와 자원 절약

동일 입력에서 같은 결정적 오류가 났으면 자동 재시도를 멈추고 FAILED 증거를 남긴다. 수정이 들어오거나 환경 복구가 입증된 경우에만 다시 요청한다. 일시적 환경 오류 재시도는 종류와 횟수를 제한한다.

동시 편집 때문에 실행이 무효화돼도 기존 오류 로그는 보존한다. 오류를 성공으로 바꾸지 않는다. 단지 그 결과가 어느 입력에 유효한지 구분한다.

worker의 대기에는 LLM 호출을 사용하지 않는다. 전체 검증 요청을 큐에 무제한 쌓지 않고 같은 대상을 병합한다. 상태 변화와 제한된 요약만 기록한다. 기존 로그 정책이 있으면 재사용한다.

## 11. 구현 순서와 완료 기준

1. 현재 훅·저널·빌드 실행 경로와 동시 편집 증거를 읽기 전용으로 조사한다. 조사 결과가 가정과 다르면 설계를 현행 구조에 맞춘다.
2. `ACCEPTANCE_TESTS.md`의 최소 경쟁 조건을 임시 폴더에서 먼저 재현한다. 무거운 Java 빌드 없이 fake writer/build로 상태 기계를 시험한다.
3. 기존 상태 저장소에 writer/검증자 상호배제, durable ticket, DEFERRED 계약을 최소 diff로 구현한다.
4. 기존 compile/test/smoke wrapper를 연결하고 잘못된 0/PASS 전파를 제거한다.
5. Codex/Devin 각각의 실제 활성 훅과 공통 스킬을 연결한다. 신뢰·권한 설정은 기존 사용자 승인 체계를 유지한다.
6. T01~T28 및 에이전트 압박 시나리오를 실행하고 명령·결과를 기록한다. 실제 설치본에서 자동 발동까지 확인한다.
7. 안정된 입력에서 실제 프로젝트 필수 검증을 실행한다. 환경 때문에 수행하지 못한 항목은 NOT_RUN과 원인을 명시한다.

성공 지표는 '코덱스가 초록색을 띄웠는가'가 아니다. 편집 중 불필요한 빌드 수, 같은 입력 재실행 수, 최대 동시 검증 수, checkpoint 후 처리 시간, 검증 없이 PASS가 된 건수, 기아/고아 ticket 수를 측정한다. 목표와 실측을 구분한다.

최종 보고 형식:

```text
변경 적용: APPLIED / NOT_APPLIED
검증 상태: VERIFIED_PASS / DEFERRED / FAILED / ...
대상: source identity + profile + scope
기다리는 대상: session/task/batch 또는 없음
요청: ticket ID + 최초 요청 시각
재개 주체: 확인된 runner identity / WAITING_FOR_RUNNER
실행한 검증: 명령·종료 코드·로그 경로
실행하지 않은 검증: 단계·사유
빌드/실행/안경 반영: 각각 별도 증거
남은 실패: 기존/신규를 근거와 함께 구분
```

## 12. 공식 문서 확인 메모

아래는 제품 기능의 근거다. 본 지시서의 상태명·시간값·CLI 설계는 별도로 제안한 프로젝트 규약이며 제품 내장 기능이라는 의미가 아니다. 2026-09-28 확인.

[1] OpenAI, Build skills: SKILL.md와 optional scripts, `.agents/skills`, 명시적/암묵적 활성화.
`https://developers.openai.com/codex/skills/`

[2] OpenAI, AGENTS.md: 작업 전 지침 로딩, 시작 위치/프로젝트 계층/크기 제한.
`https://developers.openai.com/codex/guides/agents-md/`

[3] OpenAI, Hooks: 수명주기 훅, 설정 신뢰, 복수 훅 동시 실행, Stop continuation, 도구 커버리지 한계. 문서의 현재 redirect는 ChatGPT Learn이다.
`https://learn.chatgpt.com/docs/hooks`

[4] Cognition, Devin CLI Hooks: `.devin/hooks.v1.json`, 훅별 입력/출력, exit code의 차단 의미.
`https://docs.devin.ai/cli/extensibility/hooks/overview`

[5] Cognition, Lifecycle Hooks: Stop과 SessionEnd의 차이, block하는 Stop 훅의 반복 위험, 실제 도구 이름 확인.
`https://docs.devin.ai/cli/extensibility/hooks/lifecycle-hooks`

[6] Cognition, Skills Overview: Devin CLI의 `.agents/skills`와 스킬 활성화.
`https://docs.devin.ai/cli/extensibility/skills/overview`

[7] Gradle, Continuous Builds: quiet period와 파일 감지; build logic 변경 및 감지 범위의 한계. 이것만으로 에이전트 편집 트랜잭션 완료를 판정하지 않는다.
`https://docs.gradle.org/current/userguide/continuous_builds.html`
