# agy를 Grok Bot 대타로 쓰기 — 사용자 카드

## 언제 뭘 쓰나

| | Grok Bot 앱 | agy (`Start-Agy-GrokBot.bat`) |
|---|---|---|
| 한도 | 앱 한도 소진 시 불가 | Google AI Pro 기본 한도 사용 |
| PC 파일 확인 | 느림/불가 | 빠름 — 파일·로그·sha 직접 읽음 |
| 대화 기억 | 전체 | 인계 팩(2026-10-02) + 기록부까지 |
| 사진 첨부 | 가능 | 파일 경로로 전달해야 함 |
| 제품 소스 수정 | 없음(역할상) | 없음 — STRICT_ZERO, 지시서만 씀 |

## 시작

`Start-Agy-GrokBot.bat` 더블클릭. 첫 메시지로 Grok Bot 프라이머가 자동
주입된다. 권한 자동 허용(`--dangerously-skip-permissions`)은 기본 **꺼짐** —
필요하면 환경변수 `AWX_AGY_YOLO=1`로만 켠다.

## 자주 쓰는 말 5개

1. "Devin한테 넣을 지시서 써줘 — <주제>"
2. "이 보고서 끝난거야? 더 할 거 있어?" (보고서 붙여넣기)
3. "이대로내도 돼?" (답장 초안 붙여넣기)
4. "멈췄는데 뭐라고 해야 해?"
5. "제일 큰 문제 Top10 찾아봐"

## 크레딧이 바닥났을 때

화면 아래 빨간 `Out of cre…` = **G1 추가 크레딧** 0. `useAiCredits`가 켜져
있어서 그 표시가 뜨는 거고, 기본 AI Pro 한도는 따로 살아 있다. 그래도
안 되면 그때 로그인·한도를 다시 확인한다(설정 변경은 승인 후에만).

## 기록부로 이어 쓰기

지시서는 `scripts/brief_save.py`가 Downloads `PASTE_*`와 레포
`agent-prompts\`에 둘 다 저장하고 sha12를 적는다. `brief_save.py list` /
`search <단어>`로 Grok Bot이 쓴 것과 agy가 쓴 것을 같이 찾는다 — Grok
Bot이 돌아와도 같은 기록부를 보면 된다.

## git 감시 꺼짐 안내

agy 시작 시 `Unable to start git event watcher`가 뜨면 레포
`core.repositoryformatversion=0` + `extensions.worktreeConfig=true`
조합 때문이다(보고만 하고 설정은 손대지 않았다 — ASK_ONCE #3).
