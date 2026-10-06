# DEMO1-AGY-SKILL-PACK — agy 스킬 팩(Skill Pack) 밀도 계약

> 2026-10-05 신규 (PASTE_DEVIN_agy-skill-pack-author_20261005). agy-cli가
> 스킬/지시서를 낼 때 SKILL.md 한 장만 얇게 내고 끝내는 관행을 막고,
> Grok Bot식 "한 일 = 한 primary 스킬 + companion 묶음" 산출을 기본값으로
> 잠근다. 제품 소스 계약과 무관 — 도구·룰 표면 전용이며 기존 라우터의
> primary=1(+optional≤1) 규칙은 바꾸지 않는다.

## 등급표 (S/M/L)

| 등급 | 언제 | 최소 산출 (한 일 = primary 스킬 1개) |
|---|---|---|
| **S** | 기존 스킬 문구 수정·오타·When 한 줄 보강 | 해당 `SKILL.md`만 (+ `scripts/skill_frontmatter_lint.py` PASS 권장) |
| **M** (기본 — "스킬 만들어/추가해") | 새 재사용 절차 스킬 1개 | (1) `.agents/skills/<id>/SKILL.md` 품질바 충족 (2) `.agents/skills-intent-index.yaml` intent 1건 add-only (3) companion ≥1 — `references/` 파일 · `scripts/<probe>.py`+`scripts/test_<probe>.py` · `docs/agents-rules/DEMO1-*.md` 중 하나 이상 |
| **L** (관문·탐침·방식 고정) | "앞으로 방식", ratchet, 세션 게이트, 멀티에이전트 계약 | **M** 전부 + `docs/agents-rules/DEMO1-*.md` SSOT + ratchet INV (전용 `configs/*-ratchet.json` 또는 `configs/behavior-ratchet.json` add-only) + (멀티에이전트면) `data/agent-handoff/orchestra/` 신호 한 줄 |

## 품질바 (SKILL.md — `demo1_skill_quality_audit.score_skill`과 정합)

- frontmatter `description` ≥ 40자 + use-when 트리거(언제 쓰는지 명시)
- 본문 ≥ 1500B 권장, **600B 미만은 FAIL** (감사식 −25와 정합)
- 절차(steps/numbered workflow) + 검증(verify/Acceptance/exit) +
  가드레일(forbid/never/HOLD) + 보고 형식(report/output) + 예시 1개
- primary 스킬은 정확히 1개 — `@skill` 나열로 동시 라우팅하지 않는다
  (`demo1-vibe-skill-router` primary=1, optional ≤1 유지)

## 금지 (얇은 산출 패턴)

- M/L 등급 작업인데 `SKILL.md`만 만들고 DONE 선언
- `@skill` 토큰 5개 이상 나열로 "두껍게" 흉내 — companion은 **파일 산출**이지
  태그 산포가 아니다 (`agy_skill_pack_check.py`가 `SKILL_SCATTER`로 FAIL)
- intent-index intent 누락 — 라우터가 새 스킬을 영영 못 찾는다
- 새 probe/checker 스크립트를 대응 `scripts/test_*.py` 없이 추가
- Downloads PASTE가 스킬 생성만 시키고 companion·intent·Acceptance를 생략 —
  지시서는 `directive-template.md` § SKILL_PACK 칸을 채운다

## 허용 / 비목표

- 기존 스킬의 S 등급 문구 패치(본래 용도)는 언제나 허용한다
- 이 계약은 agy가 **만드는 산출물의 밀도**를 규정한다 — 실행 권한·lease·
  제품 소스 게이트·SERIAL_LANE·STAGED_METHOD는 기존 룰 그대로이며,
  스킬 개수를 늘리라는 뜻이 아니다
- Grok Bot 선례 한 줄: "한 primary + companion 묶음" — 예: SSOT 문서 +
  스킬 + 검사 스크립트 + unittest + intent 1줄 + ratchet INV + orchestra 한 줄

## 검사

- `python -B scripts/agy_skill_pack_check.py --skill .agents/skills/<id> [--grade S|M|L]`
- `python -B scripts/agy_skill_pack_check.py --brief <PASTE 파일>` — 지시서가
  스킬+companion 경로·intent·검사 명령을 표로 약속했는지 검사
- exit 0 PASS / 1 WARN / 2 FAIL. 네트워크 0, 비밀·본문 덤프 0.
- ratchet: `python -B scripts/behavior_ratchet.py check --config configs/agy-skill-pack-ratchet.json --lock configs/agy-skill-pack-ratchet.lock.json`

## AGENTS.md 스텁 보류 (2026-10-05)

AGENTS.md가 HARD 30,300B 상한 직전(30,295B — `scripts/agents_md_budget.py`)이라
2줄 스텁 신규 등록조차 수용 불가 → 스텁 보류. 진입 경로는
`DEMO1-AGY-SPECIALIZATION.md`의 Skill Pack Author 축 + intent-index
`agy-skill-pack` + 본 문서 직접 참조로 충분하다. 재등록 조건: AGENTS.md에
스텁 1개 분(≥~330B)의 여유가 확보될 때.
