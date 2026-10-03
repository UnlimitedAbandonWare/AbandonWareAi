---
name: demo1-chat-wait-budget
description: Use when judging /chat answer wait times or timeout verdicts — chat.run.max-duration-seconds is the SSOT cap (600s) and pre-cap waits are not failures.
---

# demo1 Chat Wait Budget

디버깅 기간(2026-10-03~) /chat 답 대기 상한 = `chat.run.max-duration-seconds`
(현재 600초, env `CHAT_RUN_MAX_DURATION_SECONDS`). Codex 1,800초 지시서는 같은 키를 쓴다.

## 규칙

- 상한(600초) 도달 전 timeout은 실패 판정이 아니다 — 계속 기다린다.
- 첫 글자 지연은 관찰값으로만 기록(verdict에 쓰지 않음).
- 상한 도달 시에만 timeout 보고 + `timeoutSource`(file:line) 기록.
- 되돌리기 = `CHAT_RUN_MAX_DURATION_SECONDS=30`.
- 라이브 확인은 `http://127.0.0.1:18180/chat` 먼저; 생성 수 상한은 지시서 기준.

## 상한이 따르는 경로 (SSOT 사슬)

- 서버 선택모델 대기: `RequestedModelTimeoutPolicy.timeoutSeconds(..., maxRunDurationSeconds)` = `min(cap, requested)` — base는 빠짐.
- 입구 예산: `PublicRequestBudgetGuard` `/api/chat{,/sync,/stream}` endpointLimit = `min(max-time-budget-ms, cap×1000)`.
- 클라이언트: `chat.js streamServerBudgetMs` = meta `chat-request-budget-ms` 그대로(서버가 SSOT), 없으면 600000.
- 같은 블록 값: `llm.requested-model.timeout-seconds`(cap 기본 추종), `public.request-budget.max-time-budget-ms`(600000), `spring.mvc/webflux.async.request-timeout`(660000), BFF `RAG_BACKEND_TIMEOUT_MS` 기본(600000).

## 에이전트/테스트 대기

- 스크립트 답 대기 = `CHAT_RUN_MAX_DURATION_SECONDS + 20초`(env 없으면 620초):
  `chat_practice_browser.js`, `chat_auth_model_matrix_browser.js`,
  `phase2_full_app_browser_tests.js`, `smoke_agent_chatgpt_oauth.ps1`,
  `test_model_policy.py` (model_policy_step timeout).
- 외국 lease로 미수정(HOLD): `chat_rag_golden_browser.js`(+tests),
  `codex_browser_agent.js`(+tests), `configs/codex-browser-agent-suites.json`
  — lease `devin-browser-agent-cf4cd706` 해제 후 같은 원칙으로 맞출 것.

## 금지

- `llm.timeout-seconds` 기본값 상향, 무한 대기·0·MAX 값, 다른 채팅 경로에 영향 주는 전역 변경.
