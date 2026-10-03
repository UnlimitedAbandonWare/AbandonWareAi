---
name: demo1-main-chat-surface
description: Use when judging chat feature/UI/boot results or choosing the verification surface — primary is /chat chat-ui; the interview page is debug-only auxiliary evidence.
---

# demo1-main-chat-surface

주력 화면 SSOT: `docs/PRIMARY_SURFACE.md`. 도구 분류: `scripts/TOOL_SURFACE_MAP.md`.
주력 화면 = AbandonWare AI 메인 `/chat`(공개 `https://abandonwareai.kro.kr/chat`,
로컬 `http://127.0.0.1:18180/chat`). 면접 화면은 디버깅용(§아래).

## 판정 순서 (메인 /chat)

1. `GET http://127.0.0.1:18180/chat` (또는 `/chat-ui`) → 200 HTML.
2. chat-ui 식별: `<title>AbandonWare AI</title>` + 본문이 `/js/chat.js`를 로드.
3. 면접 표지 부재 확인: `RAG & DISPLAY STUDIO` 브랜드, `/assets/interview/` 참조,
   "INTERVIEW DEMO"류 면접 문구가 없어야 한다.
4. `demo.interview.enabled=true`면 `/chat`이 면접으로 forward돼 위 검사가 실패한다 —
   "메인 다운"이 아니라 `primary-surface-overridden-by-interview`로 기록하고 면접
   결과는 보조 증거 칸에만 둔다.
5. 기본 API 확인(선택): `POST /api/chat/sync` 빈 `message` → 400 검증 응답은 admission이
   살아있음의 증거일 뿐 실 생성 증거가 아니다(유료·실생성 호출 금지).
6. 공개 `https://abandonwareai.kro.kr/chat` 확인은 선택 사항(터널·배포 상태에 의존).

## 면접·display 자산

`static/assets/interview/*`·`assets/display/*`는 가끔 쓰는 로컬 디버깅 화면 — 지우지 않는다.
디버그 스튜디오 스킬(`demo1-debug-studio`)은 별도 작업(`devin-interview-absorb`)이 만든다.
TODO: 그 스킬이 생기면 여기에 상호 링크 한 줄을 추가한다.
