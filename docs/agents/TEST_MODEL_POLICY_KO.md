# 에이전트 테스트 모델 정책 — 한 장 카드

## 왜 생겼나
/chat 첫 화면의 기본 모델이 로컬 `qwen3.5:9b`라서(`application.properties:664` `app.ai.ui-default-model`), 에이전트가 모델을 고르지 않고내면 전부 로컬로 테스트되고 있었다. 이 정책은 **테스트하는 쪽이 모델을 명시적으로 고르게** 만든다. 제품 기본값 자체는 안 바꾼다(= Codex 몫, HOLD).

## 날짜 스위치
- **~2026-12-30 (KST, 그날 23:59 포함):** 테스트 기본 = `chatgpt-oauth:*` API 모델(ChatGPT 계정 크레딧 경로). API 후보가 전부 안 되면 `BLOCKED_API`로 멈춘다. 로컬로 조용히 내려가는 일 없음 — 로컬은 `local_fallback` 목적을 명시할 때만.
- **2026-12-31~:** `dynamic` — 목적·가용성·비용으로 로컬/API 자유 선택. 정책 파일 수정 없이 날짜로 자동 전환.

## 목적별 1순위 (상황 따라 바꿔 끼우기 — 전체 표는 `configs/agent-test-model-policy.yaml`)
| purpose | 쓰는 때 | 1순위 | 다음 |
|---|---|---|---|
| quality | RAG 정확도·최종 판정 | `chatgpt-oauth:gpt-6-astra` | sol → 5.5 |
| regression | 반복·재확인·다회 전송 | `chatgpt-oauth:gpt-5.6-terra` | luna → 5.5 |
| smoke | 전송 되는지만 | `chatgpt-oauth:gpt-5.6-luna` | terra |
| cross_provider | 다른 공급자 확인 | `llmrouter.api3` (groq) | — |
| local_fallback | 로컬 경로 자체 시험(명시적) | `gemma4:26b` | qwen3.5:9b |
| practice_chat | 가벼운 대화 연습, 말투·흐름 | `chatgpt-oauth:gpt-5.6-luna` | terra |
| practice_reasoning | 긴 추론·코드·분석 연습 | `chatgpt-oauth:gpt-5.6-sol` | astra |
| practice_compare | 같은 질문 두 모델 비교(명시적) | `chatgpt-oauth:gpt-5.6-sol` | `llmrouter.api3` |

순위 근거: OpenAI 공식 레이트카드 기준 비용·등급(`data/agent-handoff/devin-test-model-api-policy-e575d169/OFFICIAL.md`).

## 자유 연습 (브라우저로 /chat 챗봇 연습할 때도 같은 정책)
- 화면 기본 모델을 그냥 쓰지 말고 `node scripts/chat_practice_browser.js --prompts <질문파일>`을 쓴다. 질문마다 `plan`이 auto로 purpose를 분류해 모델을 다시 고르고, 모델이 바뀌면 새 대화로 시작한다.
- 수동으로는 `resolve --purpose auto --prompt-file <파일>`(분류 근거 한 줄 출력). 분류 규칙은 정책 파일 `autoClassify`에 있다(근거 키워드→quality, 첨부·코드·장문→practice_reasoning, 그 외→practice_chat; practice_compare는 자동 선택 없음).
- 기본 대상은 `http://127.0.0.1:18180/chat`. 공개 URL은 `--public`이 있을 때만, `[devin-test]` 접두어 + 최대 3회. API 생성 상한 `--max-calls` 기본 10회.

## 사용 순서 (에이전트 공통)
1. `python -B scripts/test_model_policy.py resolve --purpose <purpose>` → `selected` id를 받는다.
2. 화면 테스트면 `browser_model_select.js`의 `selectModel(page, id)`로 `#modelSelect`를 바꾼다(프로브는 `main_chat_target_probe.py --local --model-purpose <p>`).
3. 보낸 뒤 **관측 모델**(응답 `modelUsed`/`x-model-used`/trace-dock)이 고른 것과 같은지 `check`로 확인 — 다르면 `SILENT_FALLBACK`, 그 결과로 PASS 금지.
4. 매 호출 `record`, 상한 25회/실행(`budget --run <장부>`). 401/403/429면 같은 모델 재시도 0, 다음 순위 1회.

## Codex 연결 3줄 (chat_rag_golden_browser.js는 Codex 소유 — 안 건드림)
1. 실행 시작 시 `--model <id>`를 `resolve --purpose regression` 결과로 채운다(현재는 로컬 강제 `LOCAL_MODEL_REQUIRED` 경로가 있어, API 모델도 `selectOption` 되게 옵션 확장이 Codex 쪽 변경 포인트).
2. 시나리오 전송 후 `meta.observedModel||actualModel||modelUsed`를 `check --selected <요청id> --observed <관측>`에 넣는다.
3. 각 전송을 `record --purpose <p> --model <id> --code <http>`로 `usage.jsonl`에 남긴다.

## 크레딧 숫자 — 확인 필요
레포 기록은 **62,500**(`configs/agent-api-spend-guard.yaml:23`), 사용자 발언은 **6,250** — `USER_CONFIRM: 6250 vs 62500`. 실제 잔액은 관측된 적 없음. 도구는 숫자를 잔액으로 쓰지 않고 상한(25회/실행)만 지킨다.

## 되돌리는 법
이 작업이 만든 것만 지우면 된다(제품 파일 무수정):
`Remove-Item configs\agent-test-model-policy.yaml, scripts\test_model_policy.py, scripts\test_test_model_policy.py, scripts\browser_model_select.js, scripts\chat_practice_browser.js, .agents\skills\demo1-test-model-policy, docs\agents\TEST_MODEL_POLICY_KO.md, docs\agents\TEST_MODEL_BRIEF_CLAUSE.txt -Recurse -Force` — `agents.md`의 `AWX-TEST-MODEL-POLICY` 블록 3줄과 `phase2`/`main_chat_target_probe`의 `--model-purpose` 옵션 블록도 함께 제거.
