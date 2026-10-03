# GrokBot 지시서 작성 플레이북 — 상시 지침 7원칙 (SSOT)

> 출처: `C:\Users\nninn\OneDrive\Desktop\bot.txt` L13806-13820
> (2026-09-26 ~ 09-30 GrokBot·agy·Codex·Devin 대화 누적본, sha12 F5A7A70D8029)에서 정제.
> 적용 범위: demo-1 지시서 작성 — xAI 웹 GrokBot 세션과 `demo1-agy-directive-writer`(agy) 공통.
> 이 문서가 7원칙의 단독 SSOT다. 다른 문서·스킬은 여기로 포인터한다.

## 0. 진입점

| 주체 | 진입 경로 |
|---|---|
| 웹 GrokBot 세션 | `agent-prompts/grokbot-session-primer.md` (세션 첫 메시지 복붙) |
| agy (Start-Agy-CLI) | `.agents/skills/demo1-agy-directive-writer/SKILL.md` |
| 지시서 골격 | `.agents/skills/demo1-agy-directive-writer/references/directive-template.md` |
| GrokBot 세션/메모리 recall | `scripts/grok_to_agy_memory_bridge.py` → `docs/GROKBOT_MEMORY_INDEX.md` |

## 1. 입력 게이트 (최우선)

- 답변 첫머리에 첨부별 "열림/일부/못 열림"과 기준 시점(HEAD·날짜)을 표로 적는다.
- 프롬프트가 참조하는 핵심 첨부(최신 소스 번들, SSOT 문서, 테스트) 중 하나라도
  못 열었으면 분석과 지시서 작성을 멈추고 그 한 줄만 답한다:
  `"X를 열 수 없음. .md/.txt 단일 파일로 다시 첨부 요망."`
- 대체 자료나 구버전 zip으로 진행하지 않는다.

## 2. 최신본만 기준

- 스냅숏이 여러 개면 가장 최신 것만 인용한다.
- `file:line` 인용에는 출처 파일명을 붙인다.
- 구버전의 줄 번호를 최신 코드에 재사용하지 않는다.

## 3. 기존 결정 보존

- 지시서에 적힌 불변 조건(INV), 완료(DONE), 보류(HOLD) 항목을 먼저 읽는다.
- 모든 제안에 DONE / PARTIAL / NEW / CONFLICT를 표시한다.
- 기존 결정을 뒤집는 제안은 공식 근거를 붙여 ASK_ONCE에만 넣는다.
- "자료가 없어서 NEW"로 분류하는 것은 금지한다.

## 4. 증거 등급 분리

- 직접 확인 / 전달받은 보고 / 공식 문서(URL과 확인일) / 비공식 / 추론을 구분해 표기한다.
- 보지 못한 테스트 결과나 SSOT 내용은 `evidence_needed`로 둔다.
- 미확인 값은 `null`로 두고, 기본값이나 강제 거절 조건으로 쓰지 않는다.

## 5. 기본값 불변

- 새 기능 off, 유료 false, ZDR false, 상한 0.
- 실호출은 금지하고 mock만 쓴다.
- 명칭은 `docs/API_ROUTING_SPEC.md` §6을 따른다.
- commit·push 금지.

## 6. 산출 크기

- WP는 5개 이하. 각 WP에 seam(파일:메서드), RED→GREEN 테스트, 완료 명령, 금지 파일을 적는다.
- 소비처가 없는 기능은 추가하지 않는다.

## 7. 보고 순서

- ① 입력 게이트 표 → ② 결론 → ③ 한계 → ④ 본문.
- 실행하지 않은 검증은 `NOT_RUN`으로 표시한다.

## 8. 실전 트라이아지: Top10 → Shortlist3 → THE ONE

"제일 큰 문제" / "뭐부터 고쳐" 류의 열린 질문에는:

1. Top ~10 이슈를 severity 태그(P0/P1/P2)와 한 줄 실측 근거(명령+출력 또는 file:line)로 나열.
2. Shortlist 3으로 압축.
3. 정확히 하나 — THE ONE — 를 고른다. "배선이 없어서 못 찾는다"가 "로직이 나쁘다"보다 우선.
4. THE ONE에 대해서만 paste-ready 지시서를 출력; 나머지는 "다음" 목록으로 명시 유보
   (조용히 drop 금지).

단편 아이디어 증폭(연사)은
`.agents/skills/demo1-agy-directive-writer/references/idea-burst-rubric.md`의
가설 연사 → 반례/위험 → Triad 심의 → THE ONE 수렴 파이프라인을 따른다.

## 9. 관련 파일

| 파일 | 역할 |
|---|---|
| `.agents/skills/demo1-agy-directive-writer/SKILL.md` | agy 지시서 절차(입력 게이트 → 라이브 검증 → 재대조 → 역할 분담 → 작성 → 저장 → 보고) |
| `.agents/skills/demo1-agy-directive-writer/references/grokbot-playbook.md` | GrokBot 워크플로우 형태(지시서 해부·세션 수명주기·ACK·보고 계약) |
| `.agents/skills/demo1-agy-directive-writer/references/directive-template.md` | 지시서 스켈레톤(10개 섹션) |
| `.agents/skills/demo1-agy-directive-writer/references/idea-burst-rubric.md` | 단편 아이디어 증폭 루브릭 |
| `.agents/rules/agy-korean-grokbot-role.md` | agy 자동 로드 규칙(한국어·STRICT_ZERO·GrokBot 역할 계승) |
| `agent-prompts/grokbot-session-primer.md` | 웹 GrokBot 세션 프라이머(복붙용) |
