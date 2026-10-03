# Primary Surface SSOT (2026-10-01)

모든 에이전트 룰·지침·스킬·도구가 "채팅이 된다"/"기동 정상"을 판정할 때의 기준 화면.
도구 분류 표: `scripts/TOOL_SURFACE_MAP.md`.

## 1. 주력 화면: AbandonWare AI 메인 `/chat`

- 공개: https://abandonwareai.kro.kr/chat
- 로컬: http://127.0.0.1:18180/chat
- 구현: `PageController.chatUi` → `main/resources/templates/chat-ui.html` + `main/resources/static/js/chat*.js`

## 2. 디버깅 화면: 면접 화면 (곁 RAG & Display Studio)

- 위치: `main/resources/static/assets/interview/*` (`assets/display/*` 포함).
- 성격: 가끔 쓰는 **로컬 디버깅 화면** — 면접이나 사용자 대상 제품 화면이 아니다. 지우지 않는다.
- 진입: 별도 작업(`devin-interview-absorb`)이 만들 `/debug/studio`(loopback 전용, `debug.studio.enabled`).
  그 전까지는 `/assets/interview/index.html` 직접 접속.

## 3. 판정 규칙

- "채팅이 된다"와 "기동 정상"은 **메인 chat-ui** 기준으로만 판정한다.
- 면접 화면에서 얻은 결과는 보조 증거 칸에 따로 적는다 — 주력 판정에 섞지 않는다.
- `demo.interview.enabled=true` 상태에서는 `/chat`(및 `/`,`/index`,`/chat-ui`)이 면접 화면으로
  forward되므로(`PageController.java:292,298,328` + `InterviewDemoFilter`) 그 응답으로 메인을 판정하지 않는다.
- chat-ui 식별자: `<title>AbandonWare AI</title>` + `/js/chat.js` 로드 + 면접 표지
  (`RAG & DISPLAY STUDIO` 브랜드, `/assets/interview/` 참조, "INTERVIEW DEMO"류 문구) 부재.

## 4. 현재 상태와 할 일

- `main/resources/application.properties:971` = `demo.interview.enabled=true` — 플래그가 메인을 덮을 수 있다.
- 공통 기본값 `false` + interview opt-in profile 분리는 `devin-interview-absorb` Phase B가 맡는다.
  이 문서는 그 결과를 기다리지 않고 **판정 기준만** 고정한다.

## 5. 에이전트별 한 줄

- **Codex**: `/chat` 작업·검증은 chat-ui 기준; 면접 결과는 보조 증거로만.
- **Devin**: 동일. 런처·진단 판정은 `scripts/TOOL_SURFACE_MAP.md` 분류와 함께 본다.
- **Grok CLI**: 주력 = `/chat` chat-ui; 면접 = 디버깅 화면.
- **Clean (Cline)**: 주력 = `/chat` chat-ui; 면접 = 디버깅 화면.
- **Windsurf**: 주력 = `/chat` chat-ui; 면접 = 디버깅 화면.
