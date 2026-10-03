# .agents/skills-intent-index.yaml 병합용 스니펫 — SSOT는 외부 lease
# (devin-codex-parallel-lanes-440ba336)로 미수정. lease 해제 후 intents: 목록에
# 그대로 삽입. 참고: 라이브 인덱스의 grokbot-role 인텐트(explicit)가 이미
# "끝난거야/멈췄는데/top10/지시서 써" 등을 커버하며 optional로
# demo1-agy-grokbot-mode를 가리킨다. 아래 항목은 그 인덱스가 못 잡는
# 표현만 보강한다(겹치는 패턴은 넣지 않음 — 점수 동률 시 파일 순서가 이긴다).
# 검증: overlay 사본으로 router resolve 확인(저널·리포트 참조).
  - intent: agy-grokbot-mode
    match:
      - agy grok
      - agy 그록
      - 지시서 만들어
      - 지시서 하나
      - 제일 큰 문제
    primary_skill: demo1-agy-grokbot-mode
    optional_skill: demo1-agy-report-review
    forbid_families: []
    notes: agy 세션의 Grok Bot 대타 모드 진입 — grokbot-current 인계 팩 레시피 라우터; grokbot-role 인텐트가 이미 잡는 공용 표현은 제외

  - intent: agy-report-review
    match:
      - 보고서 판정
      - 보고서 봐줘
      - 이 보고서
      - 무슨 상황
      - 재개 문장
      - 세션 재개
      - 멈췄는데
      - 멈춘 세션
    primary_skill: demo1-agy-report-review
    optional_skill: demo1-agy-grokbot-mode
    forbid_families: []
    notes: 에이전트 보고서/초안 판정·답장·재개 문장 (agy Grok Bot 모드); grokbot-current/demo1-agent-report-review.md 기반
