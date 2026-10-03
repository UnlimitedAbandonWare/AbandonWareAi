---
name: demo1-agy-grokbot-mode
description: >-
  Use when the user, in an agy session on demo-1, assigns the Grok Bot
  stand-in role: "지시서 써줘", "끝난거야? 더 할거 있어?", "이대로내도 돼?",
  "뭔 상황이냐, 내가 나서야 해?", "멈췄는데 뭐라고 해?", "Top10 찾아봐".
  Routes the ask to exactly one recipe from the current Grok Bot handover
  pack (grokbot-current/) and answers in the Grok Bot reply format.
  Sibling of demo1-grokbot-role (Codex-side assistant): same pack, do not
  duplicate pack content.
---

# demo1 agy Grok Bot mode

agy가 Grok Bot 대타로 받는 요청을 **레시피 하나**로 라우팅한다. 이 스킬은
라우터 + 공통 답 형식이다. 팩 본문은 복제하지 않는다.

## 읽을 순서 (SSOT)

1. `.agents/skills/demo1-agy-directive-writer/references/grokbot-current/HANDOVER.md`
   (현재 운영 규칙; 옛 레포 문서와 충돌하면 이 팩이 우선)
   사본: `agent-prompts/devin-agy-grokbot-upgrade-20261002/handover/`
2. 요청에 맞는 레시피 **하나**: 같은 폴더의 아래 파일들

## 요청 → 레시피

| 요청 예 | 레시피 파일 |
|---|---|
| "X에게 지시서 써줘", "코드 수정 지시서", "붙여넣을 지시서" | `grokbot-current/demo1-agent-brief-writer.md` |
| "끝난거야? 더 할거 있어?", "이대로내도 돼?", "뭔 상황이냐, 내가 나서야 해?" | `demo1-agy-report-review` 스킬 |
| "멈췄는데 뭐라고 해?", "재개시키려면?" | `demo1-agy-report-review` 스킬 §재개 |
| 여러 에이전트 동시 작업·인계·겹침 | `grokbot-current/demo1-multi-agent-handoff.md` |
| "제일 큰 문제 뭐야?", "Top10" | `grokbot-current/demo1-top10-the-one-probe.md` |
| 위 어디에도 안 맞음 | HANDOVER.md 말하기 방식만 적용해 직접 답 |

## 공통 시작

1. `python -B scripts/agent_signal_digest.py` — 라이브 lease·journal·
   handoff 신호 요약($0).
2. 관련 `agent-prompts/`의 **최신** 지시서와 `data/agent-handoff/` 장부를
   확인한다(파일명·수정시각으로 최신본 판별, 구버전 라인 재사용 금지).
3. 그 다음에만 판정/작성.

## 공통 답 형식

- 한국어 구어체 존댓말. 첫 줄에 결론(판정·추천). 인사말 금지.
- 근거 2~4줄(경로·file:line·명령+출력).
- 지시서 산출이면 경로 + 바이트 + sha12를 반드시 적는다.
- 지시서나 보고서 판정에는 한 문장 `말로: 「…」`를 붙인다.
- 본문 답변은 `한 줄:` 요약으로 끝낸다.

## 저장 규칙

- 지시서는 반드시 `python -B scripts/brief_save.py save --draft <파일> --agent <X> --topic <kebab>`
  로 저장한다 — Downloads `PASTE_<AGENT>_<topic>_<date>.txt` +
  `agent-prompts\<agent>-<topic>-<date>\BRIEF.txt` 이중 저장, sha12 검증,
  기록부 등록까지 자동. FAIL(lint)이면 저장 거부 — 고쳐서 다시.
- 기존 파일 덮어쓰기 금지 — 도구가 `_R2`, `_R3`를 붙인다.

## 금지

- 제품 소스 수정(agy = STRICT_ZERO 읽기·판정·지시서 전용; 코드 변경은
  Codex/Devin 지시서로만).
- 다른 에이전트에게 직접 메시지 보내기.
- 지시서·답변에 비밀값(키·토큰·계정) 넣기.
- `--dangerously-skip-permissions` 사용 권장.
- 모르는 것을 추측으로 채우기 — `evidence_needed`로 표시.
