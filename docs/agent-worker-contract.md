# Agent Worker Contract (orchestration standard)

Multi-agent 워커(glm_worker 류 spawn_agent/followup_task 대상)의 공통 계약.
레지스트리 구현: `scripts/agent_worker_registry.py`
(저장소: `data/agent-handoff/workers/registry.json`, schema `awx.agent-worker-registry.v1`).

근거: `.devin/PROMPTS/codex-weekly-skill-directive-20260924.md` P1 —
09-23~24 오케스트레이션 집중일에 blocked/error가 몰렸고 원인 기록이 없었다.

## 생애주기

| 단계 | 행위자 | 명령 | 산출 |
|---|---|---|---|
| 등록 | 오케스트레이터 | `register --id W --role R --task T --agent A` | workerId, heartbeat TTL(기본 600s) |
| 지시 | 오케스트레이터 | `instruct --id W --text <bounded>` | history 이벤트(200자 절단) |
| 활동 | 워커 | `heartbeat --id W` (TTL 내 주기적) | liveness=fresh |
| 보고 | 워커 | `report --id W --text <bounded>` | history 이벤트 |
| 차단/오류 | 워커·오케스트레이터 | `classify --id W --state blocked\|error --cause CODE` | **원인 코드 필수** |
| 재시도 | 오케스트레이터 | `retry-decision` → `retry` | 정책 게이트 |
| 종료 | 워커·오케스트레이터 | `close --id W --result done\|aborted` | terminal |

## 원인 코드 (blocked/error 시 필수)

| code | 의미 | 일반적 대응 |
|---|---|---|
| `lease-conflict` | 대상 파일/lease 충돌 | 비중첩 대상 진행 또는 충돌 해소 후 1회 재시도 |
| `no-response` | heartbeat TTL 초과·무응답 | `sweep --mark`가 자동 분류; 재시도 전 원인 확인 |
| `context-overflow` | 컨텍스트/토큰 예산 초과 | 지시 축소·분할 후 재지시 |
| `tool-failure` | 도구 호출 자체 실패 | 동일 명령 반복 금지, 대체 경로 |
| `unknown` | 분류 불가 | 보고서에 수동 조사 표기 |

## 재시도 정책 (공유, 하드)

- 실패 워커 재시도 **최대 1회** (`retry-decision` → `retry`).
- 동일 원인 연속 2회 = `same-cause-repeated` → `abort-report`(중단·보고).
- `retries >= 1` = `max-retries-1` → `abort-report`.
- `retry` 명령은 정책이 `retry`가 아니면 거부(exit 4).

## 무응답 감지

- `sweep` = heartbeat TTL 초과 워커 목록(읽기전용).
- `sweep --mark` = 해당 워커를 `blocked/no-response`로 자동 분류·기록.
- 종료(closed/done/aborted) 워커는 sweep 대상이 아니다.

## 금지

- 원인 코드 없는 blocked/error 전이 금지 (CLI가 거부).
- 프롬프트·보고 원문 200자 초과 저장 금지, 비밀값 기록 금지.
- 레지스트리는 조정 도구이지 OS 접근 제어가 아니다 — lease/preimage 규칙과 별개로 동작.
