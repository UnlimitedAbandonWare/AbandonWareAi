# agent-machine-context-0928 — Devin/Grok/Codex 원샷 머신 컨텍스트

Contract: `DEMO1-DEVIN-AGENT-MACHINE-CONTEXT-DX-20260928`
Task: `agent-machine-context-dx-0928-64e6c087` (devin, 2026-09-28)

## 목적

에이전트가 작업할 때마다 컴퓨터 환경변수·설치 경로·도구·DB를 손으로 찾는 낭비를
없앤다. 한 명령으로 최대치 전탐침 JSON을 얻고, 쓰기는 기존 안전 레인으로 위임한다.

## 산출물

| 파일 | 역할 |
| --- | --- |
| `scripts/agent_machine_context.py` | SSOT. `python -B scripts/agent_machine_context.py [--pretty] [--section all|paths|env|tools|db|gpu|git|skills] [--write-report <path>]` → stdout 단일 JSON (`awx.agent-machine-context.v1`) |
| `scripts/agent_machine_context.ps1` | 얇은 래퍼 (python 탐침 → SSOT 호출) |
| `scripts/Agent-MachineContext.bat` | 더블클릭/에이전트용 (무인자 시 `--pretty`, `AWX_RAG_NO_PAUSE`면 pause 생략) |
| `.agents/skills/demo1-agent-machine-context/SKILL.md` | 스킬 진입점 |
| `docs/diagnostics/agent-machine-context-0928/last.json` | 실기기 1회 실행 스냅샷 (비밀 값 없음) |
| `AGENT_CONTEXT_CHEATSHEET.md` | 5줄 사용법 |

## 규칙 (요약)

- env는 **이름만** 보고 (`secretLikeNamesPresent`); 값·`.env` 내용·키 재료는 절대 출력 안 함.
- DB lane A는 `db_agent.py status` 결과를 그대로 싣는다. `locked`(exit 3)은 JVM 점유라는 **정상 응답** — 서버 kill 금지. lane B(MariaDB)는 명시 요청 시에만 (`probed:false` 고정).
- `--write-report`는 프로젝트 루트 밖 쓰기를 거부한다(exit 2).
- 쓰기 레인 위임: DB → `demo1-db-agent-cli`, 파일 → lease+checkpoint (`demo1-work-ledger`).

## 검증 (2026-09-28)

- `python -B scripts/agent_machine_context.py` → exit 0, 단일 JSON, 16개 최상위 키
- bat 래퍼 `--section gpu` → exit 0, `matchesCanonical:true`
- ps1 래퍼 `-Section env -Pretty` → exit 0
- `--write-report ..\outside-root.json` → 거부 exit 2
- `.agents/skills-intent-index.yaml` intent `agent-machine-context` 등록 완료
  (cycle-06). 최초엔 foreign live lease `uaw-harmony-foundation-0928-c50981a8`에
  막혔으나 release request(`559300f7-…`) 후 lease 종료 확인 → additive 적용.
  `resolve` 스모크: `primary: demo1-agent-machine-context`, `optional: demo1-db-agent-cli`.
