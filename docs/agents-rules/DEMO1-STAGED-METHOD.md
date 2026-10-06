<!-- BEGIN DEMO1-STAGED-METHOD -->
# STAGED_METHOD — 단계형 작업 방식 기본값 (2026-10-05)

사용자 확정 기본 작업 방식: **지시서 하나 → 스킬 resolve → 사실 확인 → 작은 단계 → 검증 → 닫기 → 다음 지시서**.
스킬 없이·단계 없이·감만으로 휘두르는 작업(야생 휘두르기)은 탐침·검사기가 걸러낸다.
대상은 모든 에이전트(Codex/dot/Devin/agy/Grok CLI)의 PASTE 실행 — 제품 소스 계약은 아니다.

## 6게이트

| # | 게이트 | 통과 증거 | 실패(야생 휘두르기) |
|---|--------|-----------|---------------------|
| G1 | PASTE/목표 1개 | SERIAL_LANE — 세션당 활성 PASTE 1개, journal 1 purpose | 한 세션에 목표 합치기 |
| G2 | 스킬 resolve | `demo1_vibe_skill_router.py resolve` 결과 또는 지시서의 `@`/`$` skill 1개 primary | 스킬 0개, `@skill` 5개+ 나열 |
| G3 | 사실 | file:line 근거 또는 `확인 필요` 표기 | 감·추측만으로 패치 |
| G4 | 작은 단계 | W0→Wn / 단계 N / WP# / G# 마커 ≥2 — 한 번에 한 seam | RED/재현 없이 대량 수정 |
| G5 | 검증 | 테스트·명령 exit·Acceptance 칸; mock≠live, 안 돌린 것은 NOT_RUN | "읽었다=끝", reject-complete에 걸리는 문구 |
| G6 | 닫기→다음 | HOLD/Acceptance 보고 후 새 PASTE·새 세션; 룰 추가는 닫힌 뒤 단건 | 패치 중 룰 대량 + 새 목표 혼합 |

## 금지 (야생 휘두르기)

- 스킬 resolve 없이 패치 시작 (G2 위반)
- 사실(file:line 또는 `확인 필요`) 없이 수정 (G3 위반)
- RED/재현 없이 대량 변경 (G4 위반)
- 한 세션에 목표 합치기 (G1 위반 — SERIAL_LANE)
- 읽기만으로 Done 주장 (G5 위반 — `reject-complete` 대상)

## 도구 호출 순서

```powershell
python -B scripts/demo1_vibe_skill_router.py resolve "<ask>"
python -B scripts/dot_brief_check.py --brief <PASTE>   # 붙이기 전
python -B scripts/staged_method_check.py --brief <PASTE>
# …작업…
python -B scripts/demo1_goal_switch_barrier.py reject-complete --text "<완료주장>"
python -B scripts/session_close_gate.py close --task <taskId>   # 해당 시
```

## 관계 — 본문은 각 SSOT에만 두고 여기는 포인터만 (복제 금지)

- SERIAL_LANE(세션당 활성 PASTE 1개): `docs/agents-rules/DEMO1-DOT-CONTROL-TOWER.md` §1-B, 잠금 INV-S1~S3
- 목표 전환 시 소유 journal/lease 닫기 + 재resolve: `docs/agents-rules/DEMO1-GOAL-SWITCH.md`, `scripts/demo1_goal_switch_barrier.py`
- primary 스킬 1개 resolve: `docs/agents-rules/DEMO1-VIBE-SKILL-ROUTER.md`, `.agents/skills-intent-index.yaml`
- 세션 종료 관문(Acceptance·checkpoint): `docs/agents-rules/DEMO1-SESSION-HARMONY.md`, `scripts/session_close_gate.py`
- 단계 확장 전 go/no-go: `.agents/skills/demo1-stepwise-ask-report/SKILL.md`
- 지시서 골격 검사: `scripts/dot_brief_check.py` (REQUIRED_SOFT에 `skill`·`stages` 포함)

## 메모

- ASSIST_PAIR(듀얼 어시스트)는 "목표 1개의 특수 형태"로만 취급한다 — 구현은 별도 지시서 몫.
- 검사기: `python -B scripts/staged_method_check.py --brief <지시서|계획>` → VERDICT PASS|WARN|FAIL + 누락 게이트명만 출력(본문 재출력 금지).
- 회귀 잠금: `configs/staged-method-ratchet.json` INV-M1~M4 (`behavior_ratchet.py --config/--lock`).
<!-- END DEMO1-STAGED-METHOD -->
