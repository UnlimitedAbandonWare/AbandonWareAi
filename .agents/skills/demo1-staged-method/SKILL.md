---
name: demo1-staged-method
description: 'Use when starting any new PASTE/directive on demo-1 or when work looks wild/random (스킬 없이·단계 없이·감으로 휘두르기) — enforces the STAGED_METHOD 6-gate order (지시서 1개 → 스킬 resolve → 사실 → 작은 단계 → 검증 → 닫기 → 다음). SSOT: docs/agents-rules/DEMO1-STAGED-METHOD.md'
---

# demo1-staged-method

모든 에이전트의 기본 작업 순서: **지시서 하나 → 스킬 → 사실 → 단계 → 검증 → 닫기 → 다음**.

## When

- 새 PASTE/지시서로 작업을 시작할 때 — 6게이트(G1~G6) 순서를 먼저 확인
- 에이전트 교대·목표 전환 직후 다음 단계를 잡을 때
- "야생/랜덤/감으로" 작업이 의심될 때 — 스킬 0개, 사실 근거 0건, 단계 표기 0건

## Do

1. G1 목표 1개 확인 — 활성 PASTE 1개, journal purpose 1개 (SERIAL_LANE)
2. G2 스킬 resolve — `python -B scripts/demo1_vibe_skill_router.py resolve "<ask>"`
3. G3 사실 확인 — file:line 근거 또는 `확인 필요` 표기 후에만 수정
4. G4 작은 단계 — W0→Wn/단계 마커로 쪼개고 한 번에 한 seam
5. G5 검증 — 실제 명령 exit·Acceptance 칸 기록; 안 돌린 것은 NOT_RUN
6. G6 닫기 — `reject-complete` → `session_close_gate.py close` → 다음 PASTE는 새 세션

붙이기 전 검사:
```powershell
python -B scripts/staged_method_check.py --brief <PASTE>   # PASS|WARN|FAIL + 누락 게이트
python -B scripts/dot_brief_check.py --brief <PASTE>
```

## Do not

- 스킬 resolve 생략, `@skill` 5개+ 나열로 라우팅 대체 (SKILL_SCATTER)
- file:line 없는 추측 패치, RED/재현 없는 대량 변경
- 한 세션에 목표 합치기, 읽기만으로 완료 주장
- Acceptance/HOLD 보고 전 새 목표 시작 — goal-switch barrier가 먼저

## SSOT

`docs/agents-rules/DEMO1-STAGED-METHOD.md` — 6게이트 표·금지·도구 호출 순서.
잠금: `configs/staged-method-ratchet.json` INV-M1~M4.
