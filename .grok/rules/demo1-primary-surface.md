# Primary surface (thin pointer)

SSOT: `docs/PRIMARY_SURFACE.md`. 이 파일은 포인터일 뿐 — 본문을 복제하지 않는다.

주력 화면 = 메인 `/chat` chat-ui(공개 `https://abandonwareai.kro.kr/chat`, 로컬
`http://127.0.0.1:18180/chat`). 면접 화면(`static/assets/interview/*`, 곁 RAG &
Display Studio)은 가끔 쓰는 로컬 디버깅 화면이다 — 지우지 않는다.
"채팅이 된다"/"기동 정상"은 chat-ui(`/js/chat.js` 로드, `AbandonWare AI` title)
기준으로만 판정하고, 면접 화면 결과는 보조 증거로만 기록한다.
`demo.interview.enabled=true` 상태의 `/chat` 결과로 메인을 판정하지 않는다.
