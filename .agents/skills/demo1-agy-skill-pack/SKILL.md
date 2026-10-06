---
name: demo1-agy-skill-pack
description: "agy가 새 스킬·규칙·탐침 절차를 만들거나 개편할 때 SKILL.md 한 장만 내지 않고 등급(S/M/L)에 맞는 Skill Pack(스킬+intent+companion+검사기)으로 묶어 산출하거나, 기존 산출물의 팩 충족 여부를 감사할 때. 기존 스킬의 문구 한 줄 수정(S) 판정이나 지시서 골격 작성 자체에는 쓰지 않음."
---

# demo1 agy skill pack

목적: "스킬 만들어" 요청의 산출물을 `SKILL.md` 단독이 아니라 등급에 맞는
**팩(pack)**으로 고정한다 — Grok Bot이 한 일에 대해 SSOT+스킬+검사
스크립트+테스트+intent+ratchet를 함께 내는 방식과 같은 밀도. 정량 기준
SSOT는 `docs/agents-rules/DEMO1-AGY-SKILL-PACK.md` — 이 문서는 절차만 둔다.

## When

- "스킬 만들어 / 추가해 / 새 절차를 스킬로", "agy가 스킬만 얇게 낸다",
  기존 산출물의 팩 충족 감사(`--skill`), 지시서(PASTE) 안의 스킬 생성
  WP가 팩을 약속했는지 검사(`--brief`).
- 형제 스킬과의 경계: 지시서 골격·저장은 `demo1-agy-directive-writer`,
  난이도 판정은 `demo1-agy-depth-router` (스킬 author 요청은 ≥L2,
  "앞으로 방식/관문/ratchet" 키워드면 L3 + 팩 L).

## Do

1. **등급 고르기** — 문구·오타·When 한 줄 수정 = S / 새 재사용 절차 =
   M(기본값) / "앞으로 방식"·관문·탐침·ratchet·멀티에이전트 계약 = L.
   애매하면 M. 등급은 산출물 요구량이지 작업 난이도가 아니다.
2. **체크리스트 채우기** — `references/pack-checklist.md`의 등급 칸:
   - M: (a) `.agents/skills/<id>/SKILL.md` 품질바 — description ≥40자+
     use-when, 본문 ≥1500B 권장(600B 미만 FAIL), 절차·검증·금지·보고·
     예시 포함, primary 정확히 1개. (b) `.agents/skills-intent-index.yaml`
     intent 1건 add-only. (c) companion ≥1: `references/` 파일 |
     `scripts/<probe>.py`+`scripts/test_<probe>.py` |
     `docs/agents-rules/DEMO1-*.md`.
   - L: M 전부 + `docs/agents-rules/DEMO1-*.md` SSOT + ratchet INV
     (`configs/<name>-ratchet.json` + `behavior_ratchet.py check→update`,
     또는 behavior-ratchet.json add-only) + 멀티에이전트면
     `data/agent-handoff/orchestra/` 신호 한 줄.
3. **검사** — `python -B scripts/agy_skill_pack_check.py --skill
   .agents/skills/<id> --grade <G>`; 지시서 산출 검사는 `--brief <PASTE>`.
   exit 0만 PASS (1=WARN, 2=FAIL). FAIL 항목은 고치지 DONE 선언 금지.
4. **보고** — verdict / 등급 / companion 경로 목록 / 미충족 항목 /
   다음 한 수, 4줄 이내.

## Do not

- M/L 요청에 `SKILL.md` 하나만 쓰고 DONE — pack check가 FAIL한다.
- `@skill` 토큰 5개+ 나열로 밀도를 대신하지 않는다 (`SKILL_SCATTER` FAIL;
  companion은 태그가 아니라 파일 산출).
- primary=1(+optional≤1) 라우터 규칙, SERIAL_LANE, STAGED_METHOD를
  팩 논리로 완화·우회하지 않는다.
- 새 probe/checker 스크립트를 대응 `scripts/test_*.py` 없이 둔다.
- pack check PASS를 제품 동작 검증으로 표기하지 않는다 — 도구·룰 표면의
  구조 감사일 뿐이다.

## Verify

```powershell
python -B scripts/agy_skill_pack_check.py --skill .agents/skills/<id> --grade M
python -B scripts/agy_skill_pack_check.py --brief <PASTE 파일>
python -B scripts/skill_frontmatter_lint.py --path .agents/skills/<id>/SKILL.md
python -B -m unittest scripts.test_agy_skill_pack_check -v
```

## Example

요청 "앞으로 스킬 만들 때 팩으로 묶어줘" → L → SKILL.md + references +
intent 1건 + `docs/agents-rules/DEMO1-*.md` + `configs/*-ratchet.json`
INV + `--grade L` PASS 후에야 보고.
