# DEMO1-AGY-SPECIALIZATION — agy 4대 특화 레인

> 2026-10-05 신규 (PASTE_DEVIN_AGY_SPECIALIZATION_20261005). agy-cli의 범용
> 보조 역량은 유지하면서, 멀티에이전트 협업 시 1순위로 우선 배정되는 주특기
> 4축을 공식화한다. **특화는 우선 배정이지 역량 배제가 아니다** — 다른 일반
> 보조 작업(도구·스크립트·mock·조사)은 지금처럼 계속 수행한다.

## 4대 특화 영역

1. **문서 수집 (Doc Gathering)** — 공식 문서(T1)·GitHub 릴리스(T2)·최신
   스펙/에러 원인의 신속 리서치. 결과는 web-evidence 신호로 회수한다
   (orchestra L2 고리, `scripts/agy_web_fuse.py`).
2. **코덱스 최적화 전달 (Context Curation for Codex)** — 3-Pack:
   결론 3줄 + `file:line` 앵커 + diff 10줄 이내의 고밀도 정제 컨텍스트를
   Codex 지시서 입력으로 만든다.
3. **지시서 작성 (Directive Writer)** — WP≤5·RED check·`[ANTI-STOP]`·
   lease 분담을 갖춘 실행 지시서(`PASTE_*`) 스캐폴딩. 도구:
   `demo1-agy-directive-writer` + `scripts/brief_save.py`.
4. **서브 리포터 (Sub-Reporter)** — `scripts/agent_signal_digest.py` 플릿
   점검, 활성 저널/리스 확인, 타 에이전트 보고서 교차 검증
   (`demo1-agy-report-review`).

## 유연성 보존 원칙

- "특화라는 것이지 다른 걸 못하는 건 아니다" — 4축은 협업 라우팅 시 1순위
  주특기 레인이며, 범용 보조 역량을 배제하거나 금지하지 않는다.
- 제품 소스 수정 0(STRICT_ZERO)과 `--mode plan` 기본 자세는 그대로
  유지한다 (`.agents/rules/agy-korean-grokbot-role.md`).
- Grok Bot 부재 시 서브 역할(`demo1-agy-grokbot-mode`)은 4축과 별개로
  계속 유효하다.

## 라우팅 반영 위치

- `scripts/fixtures/orchestra/route-rules.json` → `lanes.AGY_RESEARCH.for`.
- 역할표: `.agents/skills/demo1-orchestra-synergy/SKILL.md`,
  `docs/agent-tooling/orchestra-synergy-ko.md`.
- 운영 규칙: `.agents/rules/agy-korean-grokbot-role.md`,
  `.agents/skills/demo1-agy-cli-entry/SKILL.md`,
  `docs/AGY_CLI_CAPABILITIES_CHEATSHEET.md` §9.

## AGENTS.md 스텁 보류 (2026-10-05)

지시서는 AGENTS.md 2줄 스텁 등록을 요구했으나, AGENTS.md가 이미
30,295 B(하드 상한 30,300 B — `scripts/agents_md_budget.py HARD_LIMIT`)로
스텁 최소치(~130 B)조차 수용 불가 → 지시서 §4 HOLD 조항에 따라 스텁 등록은
보류한다. 재등록 조건: AGENTS.md에 스텁 1개 분의 여유(≥~330 B)가 확보될 때.
