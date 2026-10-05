# 3자 공명 타이밍 가이드 (agy → Devin → Codex)

agy가 증폭한 신호를 Devin(서브딜러)이 도구·룰·검증기로 다지고, Codex(메인딜러)가
제품 소스를 피니시하는 공명 사이클의 **바톤 터치 타이밍·파일 분리·lease 절차**
명문. 기존 신호 버스(`scripts/agent_quick_signal.py` / `scripts\Agent-Signal.bat`,
저장소 `data/agent-handoff/orchestra/`)를 그대로 쓴다 — 자동 전송 없음,
사용자가 PASTE를 옮긴다.

## 1. 역할과 바톤 순서

| 순서 | 에이전트 | 역할 | 산출물 | 바톤 조건 |
|---|---|---|---|---|
| 1차 증폭 | agy | 아이디어 → 신호·지시서 발신 (웹서치 근거 포함) | `web-evidence` / `devin-signal` 신호 | Devin inbox에 신호 도착 |
| 서브딜러 연타 | devin | 도구·스크립트·규칙·검증기·현황판 생산 — 제품 소스 수정 없음 | 검증기 PASS + `emit --to codex` 신호 | 도구가 exit 0으로 준비 완료 |
| 메인딜러 피니시 | codex | 핵심 자바/UI 소스 수정 + focused 검증 | patch + `patch-report` / `verify-finding` | Codex가 신호를 inbox에서 copy 후 done |

- 바톤은 **신호 한 건**으로 넘긴다:
  `cmd /c scripts\Agent-Signal.bat emit --from <me> --to <next> --summary "<1줄>" --files <산출물>`
- 받는 쪽: `inbox --agent <me>` → `copy --id <id>` → 처리 후 `done --id <id>`.
- 같은 줄기 왕복 >2회 → `ASK_USER` (`route-rules.json` roundtripLimit, loops.md와 동일).

## 2. 타이밍 규칙 (연타 무제한은 "준비"에만 해당)

- Devin은 Codex의 in-progress 저널/lease가 **돌고 있는 동안** 보조 자산을 연타 생산한다.
  Codex 완료를 기다리지 않는다 — 기다리는 것은 **검증 실행**뿐.
- Devin의 바톤 발신 시점 = 서브딜러 산출물이 존재하고 도구 헬스체크(exit 0)가 끝난 직후.
  Codex 작업 종료까지 미루지 않는다.
- Codex는 자기 patch가 끝나면 `patch-report`를 emit한다. Devin의 L4 짝 작업(verify-finding)은
  그 신호가 inbox에 뜬 뒤에만 시작한다 — 라이브 소스를 중간 검증하지 않는다.
- 서버 재시작·Gradle 스위프·유료 호출은 어느 에이전트도 신호 `budget` 필드 상향 없이는 하지 않는다
  (기본값 live 0 / restart 0).

## 3. 파일 분리(disjoint) 규칙

| 레인 | 소유 경로 예시 | 금지 |
|---|---|---|
| codex (메인딜러) | `main/java/**`, `main/resources/static/**`, `src/test/java/**` | — |
| devin (서브딜러) | `scripts/**`, `docs/**`, `.agents/skills/**.md`, `data/agent-handoff/**` | codex 소유 경로, codex가 lease 잡은 파일 |
| agy | 지시서·근거 문서 (자기 체크아웃 외부 쓰기 없음) | demo-1 소스 직접 수정 |

- 교집합 판정은 경로 접두어 기준 양방향. 겹치면 먼저 `agent_scope_lease.py check --paths ...`.
- 겹침이 불가피한 보조 파일(yml/헬퍼)은 ASK_ONCE Q-1의 (a) 조건 — 파일 비경합 확인 후에만
  자동 lease → 수정 → 즉시 release. 경합이면 스크립트·문서만 만들고 소스 수정은 보류.
- 남의 신호·저널·lease·checkpoint 파일은 절대 수정하지 않는다 (경고만).

## 4. lease 충돌 방지 절차

1. 쓰기 전 `python -B scripts/agent_preflight.py --root . --agent <me>` — 외국 활성 lease/저널 인벤토리.
2. 목표 경로가 `*.md`/`docs/`/`agent-prompts/`/`.agents/skills/`이면 lease 없이 journal+checkpoint만.
3. 소스/실행 대상이면 `agent_scope_lease.py check` → `claim`(journal+lease+claim 한 번에).
4. `check`가 exit 7(충돌)이면: 해당 경로는 제외하고 나머지 진행.
   stale(TTL/heartbeat 만료)만 `lease_conflict_autoflow.py reclaim` 격리 — live lease 강제 해제 금지.
5. heartbeat는 작업 중 주기적 갱신; 완료·중단 모두 `done`/`abort`로 release — 잔여 lease 금지.
6. `git`은 어떤 단계에서도 lease 대용이 아니다 (Git-free 계약, `demo1-work-ledger`).

## 5. 비용·금지 (공통)

- 비용 순서: codex-credit → external-paid → free → ollama-local.
- 유료 실호출 0, 웹서치는 agy 레인만. mock/dry-run 결과를 확인됨 증거로 올리지 않는다.
- 비밀값 출력 0. git push/pull/add -A/commit -a 금지. PROTO_OPEN 유지.
- 자동 전송 코드 추가 금지 — 모든 신호는 로컬 파일, 사용자가 붙여넣기로 이동.
