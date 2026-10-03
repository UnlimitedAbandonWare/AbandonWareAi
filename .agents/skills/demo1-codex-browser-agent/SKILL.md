---
name: demo1-codex-browser-agent
description: 브라우저 테스트 때 먼저 사용 — Codex가 /chat 브라우저 테스트·셀프체크·회귀 확인을 할 때 chatgpt-oauth API 모델로 넓게 자동 실행하고, 로컬 LLM은 로컬 경로 시험일 때만 쓴다.
---

# demo1 Codex Browser Agent

브라우저로 /chat을 시험하는 모든 작업의 진입점. `scripts/codex_browser_agent.js`
한 번 실행으로 smoke → golden RAG → fault → fullapp → gesture → trace 순서의
묶음을 돌리고, **실제로 답한 모델(observed)이 고른 모델과 같을 때만 PASS**다.

## 우선순위 (goal보다 위)

- AWX-TEST-MODEL-POLICY: 2026-12-30 23:59 KST까지 api_first — 테스트 모델은
  `chatgpt-oauth:*`(ChatGPT 플랜 크레딧). goal의 "local first"보다 이 정책이 우선.
- 로컬 Ollama는 `local_fallback` 목적(로컬 경로 자체를 시험)일 때만.
- 조용한 로컬 대체는 절대 PASS가 아니다: 매 전송 뒤 `check --selected --observed`,
  `SILENT_FALLBACK`/`LOCAL_BEFORE_CUTOFF`/`UNOBSERVED`/`MISMATCH`는 FAIL.

## 실행 순서

```powershell
Set-Location C:\AbandonWare\demo-1\demo-1\src
node scripts/codex_browser_agent.js --dry-run          # 계획표 6묶음, 생성 0
node scripts/codex_browser_agent.js                   # 실제 1회 실행
node scripts/codex_browser_agent.js --only golden     # 묶음 하나만
node scripts/codex_browser_agent.js --max-calls 10    # 전체 생성 상한 축소
```

1. **preflight(생성 0)**: `GET /api/chat/models`에서 selectable `chatgpt-oauth:*`
   목록 → 묶음별 purpose resolve → `test_model_policy.py budget --run <run>`.
2. 묶음 실행(`configs/codex-browser-agent-suites.json` 순서):

   | suite | tool | purpose | 생성 |
   |---|---|---|---|
   | smoke | `chat_practice_browser.js --driver browser` | auto(plan 분류) | 2 |
   | golden | `chat_rag_golden_browser.js --purpose quality` | quality | ≤10 |
   | fault | `chat_ui_browser_fault_fixture.js`(합성 SSE) | 없음 | 0 |
   | fullapp | `phase2_full_app_browser_tests.js --model-purpose regression` | regression | ≤5 |
   | gesture | `chat_auth_model_matrix_browser.js --live`(있고 그 tests가 통과할 때만) | smoke | ≤6 |
   | trace | `trace_dock_browser_probe.js` | 없음 | 0 |

3. 판정: 묶음별 PASS/FAIL/BLOCKED/NOT_RUN + selected vs observed + ms +
   스크린샷 경로를 `<ledger>/evidence/browser-agent-<ts>.{md,json}`에 남긴다.
   답변 원문은 저장하지 않는다.

## 상한·안전 규칙

- API 생성 합계 ≤25/run (`budget --run <run>`으로 확인; 초과 예상 묶음은 NOT_RUN).
- 공개 URL 전송 0 — loopback(127.0.0.1)만 허용.
- 401/403/429 → 같은 모델 재시도 0, 다음 정책 순위로 1번만.
- API 후보가 전부 죽으면 BLOCKED_API → 이후 생성 묶음 전부 NOT_RUN.
  로컬 대체 실행 금지.
- 직접 /api/chat/sync를 쏘는 도구는 반드시 `X-Budget-Ms: 30000` 헤더를 넣는다
  (헤더 없는 요청의 기본 예산은 1500ms — OAuth 호출이 `before_headers_timeout`으로 죽음).
  브라우저 driver 경유 시 chat.js가 헤더를 자동으로 넣는다.

## 실패 분류 → 다음 행동

| 신호 | 의미 | 행동 |
|---|---|---|
| 401/403 | 토큰/권한 문제 | 고치지 말고 보고(키·플랜 영역) |
| 429 | 쿼터 | 동일 — 재시도 금지 |
| `before_headers_timeout`/`chatgpt_oauth_deadline_exhausted` | 요청 예산 1500ms 기본값에 걸림 | 발신 도구에 `X-Budget-Ms: 30000` 추가(executor측) |
| `chatgpt_oauth_stream_required` | upstream이 200인데 SSE가 아님 | 제품/엔드포인트 계약 문제 — 보고만 |
| `provider_not_configured_strict` | 라우팅 설정 누락 | 보고만(키 열람·추가 금지) |
| `MODEL_NOT_SELECTABLE`/`POLICY_RESOLVE_FAILED` | 카탈로그·정책 불일치 | resolve 출력과 카탈로그를 같이 보고 |

## 재사용 도구(새로 만들지 말 것)

`test_model_policy.py`(resolve/plan/check/record/budget), `browser_model_select.js`,
`chat_practice_browser.js`, `chat_rag_golden_browser.js`, `chat_ui_browser_fault_fixture.js`,
`trace_dock_browser_probe.js`, `smoke_agent_chatgpt_oauth.ps1`, `main_chat_target_probe.py`,
`fault_matrix_harness.py`, `phase2_full_app_browser_tests.js`.

관련 스킬: `demo1-test-model-policy`(정책 SSOT), `demo1-codex-oauth-route-assist`(OAuth 경로 분석),
`demo1-codex-auth-model-browser-matrix`(gesture 묶음의 원본 도구).
