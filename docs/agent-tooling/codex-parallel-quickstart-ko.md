# Codex 병렬 채팅 운영 — 빠른 시작 (한국어)

같은 checkout(`C:\AbandonWare\demo-1\demo-1\src`)에서 Codex 채팅 여러 개를
**일부러 동시에** 돌릴 때 쓰는 절차다. 새 잠금도 데몬도 없다 — 기존 장부·
lease·checkpoint 위에 레인 계획과 쿼터만 얹는다.

## 0) 언제 쓰나

- 하나의 지시서가 파일 여러 묶음을 건드려 한 채팅에 넣기 크다.
- 같은 목표를 두 채팅이 동시에 받아 중복 실행된 사고(2026-10-02) 재발 방지.
- 채팅 하나는 수정, 다른 하나는 검증·인계 대기로 나누고 싶을 때.

## 1) 레인 나누기 (한 번만)

```powershell
python -B scripts/codex_lane_plan.py --brief agent-prompts\<지시서>\BRIEF.txt --lanes 3
# 또는 명시적으로:
python -B scripts/codex_lane_plan.py --goal-key DEMO1-XXX-날짜 `
  --wp "failover=main/java/.../FallbackAwareChatModel.java,src/test/java/.../FailoverTest.java" `
  --wp "warmup=main/java/.../LocalLlmProcessManager.java" --lanes 3
```

산출물: `data/agent-handoff/parallel-lanes/<planId>/` 안에
`plan.json`, `lane-A.txt`, `lane-B.txt`, …, `PLAN_KO.md`.
같은 파일을 쓰는 WP는 자동으로 같은 레인에 묶인다. 겹침을 못 피하면
`SERIAL_REQUIRED`로 답한다 — 그때는 채팅 하나로 순차 실행.

## 2) 채팅 열고 머리말 붙이기

- 레인 하나당 Codex 채팅 하나. 각 채팅 **맨 위에** `lane-<id>.txt` 전체를
  붙여 넣고 그 아래 지시서를 붙인다.
- 머리말 예:
  `[LANE: plan-xxxx/A of 3 role=OWNER buildHostId=codex-lane-a write=main/java/...,src/test/java/... smoke=0 restart=no]`
- `write=` 목록이 계약이다. 범위 밖 파일이 필요하면 직접 고치지 말고
  INTEGRATOR 레인에 인계한다.

## 3) 각 채팅의 첫 행동 — preflight

```powershell
python -B scripts/codex_parallel_preflight.py --root . `
  --goal-key "<Contract 또는 목표키>" --lane "<planId>/<laneId>" `
  --scope "<write 경로들>" --agent <이름> --json
```

판정과 해야 할 일:

| verdict | role | 의미 / 행동 |
|---|---|---|
| `CLEAR` | OWNER | 정상 착수. `nextCommands`의 claim → writer-begin → quota 순으로 진행 |
| `DUPLICATE_GOAL_LIVE` | VERIFIER | 같은 목표의 살아있는 채팅 있음 → 읽기 전용 검증만, 수정 금지 |
| `DUPLICATE_GOAL_QUIET` | TAKEOVER | 상대가 오래 조용 → 상대 checkpoint postimage를 baseline으로 인수 |
| `DUPLICATE_GOAL_HANDOFF` | TAKEOVER | 상대가 handoff.json 남김 → 그 패킷 필드대로 인수 |
| `FOREIGN_CLAIM_OVERLAP` | WAIT | 다른 목표가 범위 일부 점유 → `freeScope`만 진행하거나 대기 |
| `LANE_VIOLATION` | — | 레인 계획과 실제가 어긋남 → 멈추고 정리 |

추가로 `unclaimedEdits[]`(claim 없이 최근 바뀐 파일), `staleClaims`
(dry-run 후보 — 회수는 기존 `agent_scope_lease.py reclaim`만)를 같이 보여
준다.

## 4) 공유 자원 쿼터

```powershell
python -B scripts/codex_lane_quota.py acquire --plan <planId> --lane A --resource chat-smoke --agent <이름>
python -B scripts/codex_lane_quota.py status  --plan <planId> --json
python -B scripts/codex_lane_quota.py release --token <토큰ID>
```

- `server-restart`·`ollama-gpu-load`: 상호배제 1개(기본 INTEGRATOR 전용).
- `chat-smoke`: plan 합산 기본 **2회**. 세 번째는 거절된다.
- `gradle`: 동시 2개, `buildHostId` 중복 금지
  (`-Pawx.buildHostId=codex-lane-a` 그대로 사용).
- `project-status-append`: `docs/PROJECT_STATUS.md` 쓰기는 INTEGRATOR만.

## 5) 보고싶을 때 — 보드

```powershell
python -B scripts/codex_lane_board.py --plan <planId> --json
```

레인별 역할·채팅·마지막 활동·쓰는 파일·스모크 사용량·journal 상태 표.

## 6) 인계 (채팅을 닫거나 넘길 때)

- 종료하는 쪽이 `work_journal.py handoff`로 `handoff.json`을 남긴다
  (필드 예시: `scripts/fixtures/parallel_lanes/handoff-packet-example.json`).
- 이어받는 채팅은 preflight가 `TAKEOVER`를 내고 `baseline.postimages`로
  상대 마지막 checkpoint 상태를 기준 삼는다.
- 채팅 간 메시지 승인 요청이 오면: **인계·완료 통지일 때만 "허용"**.
  진행 중 잡담성 메시지 승인은 판단을 흐리니 미뤄도 된다. 메시지 자체는
  근거가 아니라 `handoff.json`이 근거다.

## 7) 마지막 — INTEGRATOR 통합 게이트

```powershell
python -B scripts/codex_lane_integrate.py check --plan <planId> --json
```

- `READY_FOR_INTEGRATION_TEST` → 출력된 통합 테스트 명령 한 줄을
  INTEGRATOR 채팅이 직접 실행.
- `BLOCKED(이유들)` → 열린 journal, postimage drift, LANE_VIOLATION,
  foreign-hunk lost 등 이유를 그대로 보여 준다.

## 8) 절대 금지 (레인 운영 중 공통)

- write 범위 밖 수정, VERIFIER 레인의 수정, 쿼터 초과 스모크/재기동.
- 다른 세션 lease 강제 회수, 남의 journal/handoff 수정.
- push·commit 등 Git 변경 (기존 규칙 그대로).

2026-10-02 v2 timeout 지시서 리허설 결과와 사고 재구성:
`docs/agent-tooling/parallel-session-incident-20261002.md`,
`codex-parallel-sessions.md`.
