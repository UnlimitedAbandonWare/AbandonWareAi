# Failure Archive Taxonomy (성유물형 실패 분류 체계)

SSOT for `scripts/failure_archive.py` and the registry at
`data/agent-handoff/failure-archive/failures.jsonl`.

다중 에이전트(Grok, Devin, Codex, Clean, agy, GPT Pro)의 실패·에러 보고서를
원신 성유물 분류처럼 다섯 축으로 규격화한다. 목적은 "임무 실패"와 "시스템
에러"가 뒤섞인 보고서 묶음에서, 최근 세션이 아니어도 맥락을 즉시 식별하고
보존 상태를 구분하는 것이다.

## 레코드 스키마 (`awx.failure_archive.v1`)

| 성유물 비유 | 필드 | 값 |
|---|---|---|
| 슬롯(Slot) | `slot` | 보고 주체 에이전트: `CODEX`(로직), `DEVIN`(환경/검증), `GROK`(도구), `CLEAN`(룰), `AGY`(지시서), `GPTPRO`(샌드박스) |
| 세트(Set) | `set` | 서브시스템 도메인: `rag-core`, `display-meta`, `oauth-auth`, `lms-runtime`, `ops-tooling`, `api-provider` |
| 주옵션(Main Stat) | `mainStat` | 실패 유형 — 아래 표 |
| 부옵션(Sub Stats) | `subStats` | `severity`, `reproducibility`, `recoverability`, `evidenceTier` |
| 보존 상태 | `status` | `LOCKED_ACTIVE`, `ENHANCED_RESOLVED`, `FODDER_SUPERSEDED`, `ARCHIVED_MUSEUM` |

고유 ID는 `FA-<YYYYMMDD>-<HEX4>`다.

## 주옵션(Main Stat / Category)

| 값 | 라벨 | 정의 |
|---|---|---|
| `TASK_FAILURE` | 임무 실패 | 코드는 완주했으나 지시 목표 미달성, 검증 실패, 로직 오답, 거짓 완료 보고 |
| `RUNTIME_ERROR` | 시스템 에러 | 빌드 중단, 문법 오류, 미처리 예외(NPE, 500), 비정상 종료(exit != 0) |
| `TOOL_DEFECT` | 도구 결함 | CLI 인자 오류, 파싱 에러, 경로 불일치, 인코딩 충돌 |
| `LOCK_COLLISION` | 선점 충돌 | 활성 lease 충돌, 파일 잠금, 쓰기 차단 |
| `SPEC_API_DRIFT` | API/스펙 드리프트 | 401/403/429, 할당량 초과, 의존성 불일치, 외부 API 변경 |
| `GUARD_BREACH` | 가드 위반 | 제한 규칙 위반 시도, 금지된 제품 소스 직접 수정 차단 |

핵심 구별: `TASK_FAILURE`는 실행이 끝났는데 목표가 틀렸거나 검증이 안 된
경우다. `RUNTIME_ERROR`는 실행 자체가 비정상 종료된 경우다. 프로세스가
죽었으면 `TASK_FAILURE`로 기록하지 않는다.

## 부옵션(Sub Stats)

| 필드 | 값 |
|---|---|
| `severity` | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` |
| `reproducibility` | `DETERMINISTIC` / `INTERMITTENT` / `ENV_DEPENDENT` |
| `recoverability` | `AUTO_RETRYABLE` / `PATCH_REQUIRED` / `USER_DECISION` |
| `evidenceTier` | `live_success` / `degraded_honest` / `provider_direct_only` / `auth_blocked` / `not_observed` |

`evidenceTier`는 `awx.debug.verify.v2`의 판정 등급과 맞춘다. 관찰되지 않은
항목은 `not_observed`이며 PASS로 간주하지 않는다.

## 보존/잠금 상태(Status)

| 값 | 의미 |
|---|---|
| `LOCKED_ACTIVE` | 미해결 활성 실패 (별표 잠금, 현재 해결 필요). `record` 기본값 |
| `ENHANCED_RESOLVED` | 후속 작업으로 해결 완료 (`resolve` — 해결 task/commit ref 링크) |
| `FODDER_SUPERSEDED` | 낡은 세션의 무효화된 과거 보고서 (`supersede` — 갈갈이/무시 가능) |
| `ARCHIVED_MUSEUM` | 재발 방지 선례용 영구 박제 보관 (수동 승격; CLI 미구현) |

## CLI

```powershell
python -B scripts/failure_archive.py record --agent DEVIN --category TASK_FAILURE `
  --domain ops-tooling --title "<한 줄>" --evidence "<근거 경로/설명>" `
  --repro DETERMINISTIC --severity HIGH
python -B scripts/failure_archive.py list [--status ALL] [--category X] [--agent Y] [--domain Z] [--json]
python -B scripts/failure_archive.py show FA-YYYYMMDD-XXXX [--json]
python -B scripts/failure_archive.py resolve FA-YYYYMMDD-XXXX --by <taskId-or-ref> --note "<메모>"
python -B scripts/failure_archive.py supersede FA-YYYYMMDD-XXXX --reason "<사유>"
```

`--root`로 레지스트리 루트를 바꿀 수 있다(테스트는 임시 경로 사용).
`--recoverability`, `--evidence-tier`는 선택 인자다.

## 경계

- 등록은 관찰된 사실만: 비밀값(API 토큰, 키 본문, 비밀번호)은 거부된다.
- 레지스트리는 증거이지 승인이 아니다: `ENHANCED_RESOLVED`는 연결된 해결
  근거(`--by`)가 있을 때만 의미가 있다.
- `FODDER_SUPERSEDED`는 삭제가 아니다. 레코드는 남되 기본 목록에서 제외된다.
