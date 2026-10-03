# DEMO1-MODEL-DEFAULT-AUTH-FIRST

채팅·에이전트 기본 모델은 auth(ChatGPT OAuth) 모델부터 — 로컬 Ollama는 마지막 폴백.

## 규칙

- 기본 선택 모델 = `chatgpt-oauth:gpt-5.5` (route id 형식; slug `gpt-5.5`). 근거: `data/agent-handoff/chatgpt-oauth/CODEX_MODEL_MATRIX.md` 역할표 — main RAG chat / anonymous `/api/chat/sync` default = gpt-5.5, 유일한 general(non-code_mode) slug.
- 모델 탐색·선택은 **auth → 외부 유료 API → 무료 → 로컬 Ollama** 순서로 시작한다. 비용 순서(AGENTS.md 코스트 규칙)와 동일.
- 로컬 `gemma4:26b` 는 **마지막 폴백 전용**이다. 기본값·첫 후보로 쓰지 않는다. `llm.chat-model`/`LLM_CHAT_MODEL` 계열 기본값은 로컬 폴백 체인이므로 유지한다.
- auth 실패(`reasonCode=chatgpt_oauth_*`)는 같은 요청에서 재시도하지 않고 다음 순위로 내린다. 원인 reasonCode 는 로그에 남긴다. **401/403/429 재시도 금지** — 다음 순위로 한 번만 이동.
- `chatgpt-oauth:` id 는 `ModelCapabilities.isRemoteLookingModelId` 에서 remote-looking 이 아니다 → `app.ai.allow-remote-model-selection=false` 에도 선택 가능(별도 remote flip 불필요).
- `chatgpt-oauth:codex-auto-review` 는 채팅 모델이 아니다 — 선택지·기본값에서 제외.

## 적용 지점 (SSOT)

- 런처 로컬 기본값: `scripts/start_rag_stack.ps1` `$defaults` 의 `APP_AI_UI_DEFAULT_MODEL` / `APP_AI_DEFAULT_MODEL` / `OPENAI_API_MODEL_DEFAULT` (process-local, 기존 env 가 있으면 덮지 않음).
- 제품 기본값 변경 = Codex 소유 패치 (준비안: `data/agent-handoff/devin-auth-model-default-b194714b/codex_patch_auth_default.md`). 게이트: OAuth stream fix 반영 + wp3 lease 해제 후.
- 에이전트 테스트 선택: `configs/agent-test-model-policy.yaml` — 이미 auth-first (`apiFirstUntil` 게이트 + `local_fallback` purpose 만 의도적 로컬 전용).

## 확인 도구

```powershell
$PY -B scripts/model_default_probe.py --json
```

verdict: `AUTH_FIRST_OK` / `AUTH_DEFAULT_BUT_ROUTE_BROKEN` / `LOCAL_DEFAULT_VIOLATION` / `API_DEFAULT_INTERMEDIATE` / `STATIC_*`(서버 다운 시 정적 판정).
