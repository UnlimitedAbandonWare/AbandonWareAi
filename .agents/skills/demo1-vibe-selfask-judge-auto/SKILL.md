---
name: demo1-vibe-selfask-judge-auto
description: Use when a demo-1 vibe task is about to ask the user an approval quiz (1/2/3 choice) — run the POSITIVE/NEGATIVE/COUNTEREXAMPLE self-ask then a NEUTRAL JUDGE that emits exactly one verdict AUTO / ASK_ONCE / HOLD; reversible local paths go AUTO, only irreversible asks reach the user (one question max).
---

# demo1-vibe-selfask-judge-auto

Contract `DEMO1-DEVIN-SELFASK-JUDGE-AUTO-VIBE-20260929`. 목적: 바이브 작업 중
승인 퀴즈(1/2/3 골라라)를 줄인다. 사용자에게 묻기 **전에** 아래 루프를 먼저
돌리고, 되돌릴 수 있는 로컬/최소 변경은 AUTO로 진행한다. 불가역만 ASK_ONCE
1회. 이 스킬은 always-on 규칙의 「가설→반례→중립 판정」을 pre-ASK 게이트로
구체화한 것이다 — `$demo1-triad-deliberation`/`$positive-negative-neutral-judge`
의 무거운 심의를 대체하지 않고, `$demo1-stepwise-ask-report`의 scope 분류와
모든 hard constraint(lease·secret·git remote 금지)는 그대로 유지한다.

## The loop (mandatory before any user-facing ASK)

1. **POSITIVE** — 최소 시임(minimal implementation)·로컬·가역 최선안 1개를 고르고
   근거(evidence: live 파일/명령 출력/기존 SSOT 경로)를 적는다.
2. **NEGATIVE** — HOLD 또는 축소(shrink) 대안과 위험 이유를 적는다.
   POSITIVE가 치는 경계(lease·foreign journal·hard rule)를 명시한다.
3. **COUNTEREXAMPLE** — POSITIVE가 깨지는 실패모드 1–2개를 구체적으로 적는다
   (예: 건드린 파일이 foreign lease 안, dry-run이 실제 쓰기와 다름).
   반례가 살아있으면 POSITIVE는 축소되거나 폐기된다.
4. **NEUTRAL JUDGE** — 위 셋을 보고 정확히 하나만 출력한다. 창발·비유·
   옵션 나열 금지:

   - `AUTO:<choice> + reason + evidence` — 사용자에게 묻지 않고 진행.
   - `ASK_ONCE:<불가역 대상> + question(1개만)` — 불가역/비용/정책 소유 영역이고
     가역 로컬 경로가 없을 때만. 질문은 하나. 두 개 필요하면 HOLD.
   - `HOLD:<blocker> + resume condition` — AUTO도 단일 질문도 공정하지 않을 때.

## AUTO-eligible (local, reversible, evidence-backed)

- 로컬 additive DDL — 새 테이블/컬럼 추가만, `db_agent.py` dry-run→apply 경로,
  기존 row/migration body 파괴 없음
- GATE/probe 스크립트, read-only 진단·스냅샷 (`f01b_schema_gate_probe.py` 등)
- Declared scope 안의 좁은 패치 — checkpoint preimage 보존된 것
- PROTO_OPEN prototype admin 확인이 live에서 `NOT_OBSERVED`일 때 — 기록만, 배선 없음
- Soft git/index.lock 대기, own lease heartbeat·재시도
- 스킬·docs·agent-prompts·status doc 같은 artifact 작성/수정

## ASK_ONCE / STOP (irreversible or forbidden — never AUTO)

- 운영·공유 DB 쓰기, DELETE/TRUNCATE/DROP, 적용된 migration body 수정
- `git commit`/`push`/`add -A`/foreign staged path 탈취 — conditional local Git
  단일 진입점 `scripts/agent_git_vibe_commit.py` 외 금지
- `task_ask`·callback·InMemory queue 활성화, n8n callback 배선
- Secret 값 출력·로그·커밋 (env 이름만 — 항상)
- Autograde B·F02 scanner를 명시 contract 없이 재개봉
- 다른 remote 추가·변경, 또는 `origin`이 `AbandonWareAi`가 아닌 상태로 진행
- foreign lease·foreign `in_progress` journal 범위 덮어쓰기
- `$demo1-agent-api-spend-guard` SSOT 밖 유료 provider 생성 (kill switch
  `AWX_AGENT_ALLOW_PAID_MODELS=0` 무시·dedupe 없는 fanout)

## Journal line (after every verdict)

```powershell
python -B scripts/work_journal.py note --root . --task <taskId> --kind plan `
  --text "SELFASK_JUDGE AUTO|ASK_ONCE|HOLD | <reason> | <paths>"
```

AUTO라도 기록한다 — 판정 자체가 증거이며 다음 세션의 재질문을 막는다.

## Anti-patterns

- 가역 로컬 선택지를 "1/2/3 골라주세요"로 묻기 → 루프를 돌려 AUTO로 결정.
- ASK_ONCE에 질문 2개를 구겨 넣기 → 하나만, 아니면 HOLD.
- 루프로 hard constraint(lease/secret/git remote/flag)를 약화시키기 — 불가.
- POSITIVE가 안전해 보인다고 COUNTEREXAMPLE 생략 — 반례가 요점이다.
- 같은 판정을 저널한 뒤 동일 조건으로 재질문 — `SELFASK_JUDGE` 행을 먼저 확인.
- 대량 sweep·다중 파일 동시 적용을 AUTO 하나로 포장 — change-set은 bounded 유지.

## Related

- `$demo1-codex-auto-decide` — 질문 유형별 기본 답 표(D1~D12): 표에 매치되면 self-ask 루프 없이 AUTO + `AUTO_DECISION:` 기록
