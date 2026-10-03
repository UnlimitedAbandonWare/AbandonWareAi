---
name: demo1-debug-studio
description: Use when opening the local-only debug studio /debug/studio or /debug/display — 면접·디스플레이 정적 화면을 테스트 브라우저로 쓰는 진입점. 켜는 법, 게이트, 금지 사항 한 장 요약.
---

# demo1-debug-studio

면접 데모 화면(`static/assets/interview/index.html`)과 디스플레이 화면(`static/assets/display/index.html`)을
사이트 전체를 덮지 않고 로컬에서만 여는 경로. `DebugStudioController`가 forward한다.

## 라우트

| 경로 | 실제 화면 | 조건 |
|---|---|---|
| `GET /debug/studio` | `forward:/assets/interview/index.html` (곁 RAG & Display Studio) | `debug.studio.enabled=true` + loopback |
| `GET /debug/display` | `forward:/assets/display/index.html` | `debug.studio.enabled=true` + loopback |

## 켜는 법

- 로컬 dev 프로파일(`application-local.yml`)에 `debug.studio.enabled=true`가 이미 들어 있다.
- 다른 프로파일: `--debug.studio.enabled=true` 또는 `application-<profile>.properties`에 추가.
- `demo.interview.enabled`와 무관하게 동작한다(단, `interview` 프로파일 ON에서는 `InterviewDemoFilter`가 `/debug/**`를 404로 걸러 못 연다 — 스튜디오는 메인/로컬 프로파일 전용).

## 게이트(의도된 동작)

- 플래그 OFF → 컨트롤러 빈 자체가 없음 → 404.
- 비-loopback 원격 주소 또는 Host 헤더가 loopback이 아닌 요청(예: cloudflared 터널의 `abandonwareai.kro.kr`) → 404.
- `GET`만 열린다. 다른 메서드는 매핑이 없다.
- 면접 화면에는 상단 "DEBUG STUDIO — 로컬 전용" 배너와 `noindex` 메타가 있다.

## 주의

- 이 화면에서 `/api/assist/**` 호출은 메인 프로파일에서 익명에게 닫혀 있다(기본 체인 `authenticated`). 디스플레이 전송(`/card`, `/output/poll`)은 `demo.interview.enabled=false`에서 `demo_disabled` 404. 테스트 브라우저로 RAG 쪽만 검증하거나, 전송까지 보려면 `interview` 프로파일로 별도 실행한다.
- 비밀값·쿠키·토큰을 화면/로그에 출력하지 않는다.
- Codex Browser 플러그인용 상세 절차: `data/agent-handoff/devin-interview-absorb/FOR_CODEX_DEBUG_STUDIO.md`.
