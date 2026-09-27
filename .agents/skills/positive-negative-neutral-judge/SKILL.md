---
name: positive-negative-neutral-judge
description: "Use when the user wants positive/negative/neutral cross-check, triad deliberation"
triggers:
  - user
  - model
---

# positive-negative-neutral-judge

사용자가 자주 쓰는 삼중 심의(스터프2)를 Devin에서 재사용하는 Skill.
기존 상세 절차 SSOT: `.agents/skills/demo1-triad-deliberation/SKILL.md` — 충돌 시 그쪽을 읽고 따르되, 아래 출력 계약을 우선한다.

## Roles
1. **긍정**: 성공 가설·기회·잘 풀렸을 때 시나리오 2~3개. 약한 신호는 라벨.
2. **부정**: 실패·위험·긍정 측이 놓친 반례.
3. **중립(심판)**: 과장 vs 가능성 판정. 비유 금지. 차분한 말투. 사실/추정 구분. 온도 낮게(창발·무작위 아이디어 금지).

## Cross-check
- 세 경로의 질의·근거를 교차해 편향 답을 걸러내고, 반례를 견디는 일관된 결론만 채택.
- 웹은 **공식 SSOT 사실확인**에만 사용. 합의≠검증. 코드·테스트·실제 근거가 우선.
- `*` 자리에 알맞게 채우라는 요청이면, 모르는 항목만 웹으로 채워 넣는다.

## Output contract
- 상투적 서두 없이 **최종 판정을 최상단**에 즉시.
- 그다음 근거. 추측·반복·과격 표현 줄이기.
- 우선순위: 사용자 명시 → 도메인 표준 → 본 규칙 → 휴리스틱.
- 최소 diff. 비밀·파괴 금지. 「모드」라는 말 쓰지 말 것.
- 되돌릴 수 있는 다음 시도에는 기대효과·확인지표·중단조건.
- 신체 위험(렌즈 등)은 엄하게 경고. 하드웨어 비정석도 대안으로 인정하되 이점·리스크 함께.
- apikey는 환경변수 참조만. openssl 키 이름·값·형식·구조 절대 변경 금지.

## Project rule
Always-on contract lives in `AGENTS.md` and `.windsurf/rules/demo1-hard-constraints.md`.

