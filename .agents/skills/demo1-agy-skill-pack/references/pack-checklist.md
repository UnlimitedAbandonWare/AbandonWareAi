# Skill Pack checklist (등급별 빈 칸)

순서: 등급 선택 → primary 스킬 이름 확정 → 칸 채우기 → check 실행.
빈 칸이 남으면 FAIL이거나 아직 안 만든 것이다.

## 0. 등급 선택

- [ ] **S** — 기존 스킬 문구/오타/When 한 줄 (해당 `SKILL.md`만)
- [ ] **M** — 새 재사용 절차 (기본값; "스킬 만들어/추가해")
- [ ] **L** — 방식 고정·관문·탐침·ratchet·멀티에이전트 계약 (M + §2)

## 1. M 칸

- [ ] `.agents/skills/<id>/SKILL.md` — description ≥40자+use-when 트리거,
      본문 ≥1500B 권장(**600B 미만 FAIL**), 절차·검증·금지·보고 형식·예시
      포함, primary 스킬 정확히 1개
- [ ] `.agents/skills-intent-index.yaml` — intent 1건 **add-only**
      (match 패턴이 실제 사용자 문구를 커버; `fallback:` 블록 직전에 추가)
- [ ] companion ≥1 (쓴 경로를 적는다):
      `references/<파일>` | `scripts/<probe>.py` + `scripts/test_<probe>.py`
      | `docs/agents-rules/DEMO1-*.md`

## 2. L 추가 칸

- [ ] `docs/agents-rules/DEMO1-*.md` SSOT (등급표·금지·허용·검사 명령)
- [ ] ratchet INV — `configs/<name>-ratchet.json` 작성 후
      `python -B scripts/behavior_ratchet.py check --config <cfg> --lock <lock>`
      → `update` (또는 `configs/behavior-ratchet.json`에 add-only)
- [ ] 멀티에이전트 계약이면 `data/agent-handoff/orchestra/` 신호 한 줄
      (`awx.orchestra-signal.v1` — 자동 전송 없음, 사용자가 붙인다)

## 3. 검사

- `python -B scripts/agy_skill_pack_check.py --skill .agents/skills/<id> --grade <S|M|L>`
- `python -B scripts/agy_skill_pack_check.py --brief <PASTE 파일>` — 지시서가
  스킬·companion 경로·intent·검사 명령을 표로 약속했는지

## 4. 금지 상기

- `@skill` 5개+ 나열 = `SKILL_SCATTER` FAIL (companion ≠ 태그 산포)
- test 없는 새 probe 스크립트 금지 · primary=1 라우터 규칙 불변
- pack check PASS ≠ 제품/라이브 검증
