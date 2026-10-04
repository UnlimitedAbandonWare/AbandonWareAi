---
name: demo1-api-routing-inventory
description: "Use when adding or changing search, RAG, transcription, embedding, LLM, vision"
---

# Demo1 API Routing Inventory

## When

Any task that calls or configures Brave/Tavily/SerpAPI/Naver, Soniox/Deepgram, Ollama/OpenAI/Groq/Gemini, Pinecone/Upstash, or model routing.

## Required reads

1. `docs/API_ROUTING_SPEC.md`
2. `configs/api-routing.yaml`
3. `references/live-inventory.md` (refresh if older than a few days)
4. `$demo1-local-llm-gpu-gateway` for local GPU/Ollama seams
5. `$demo1-rag-platform` for RAG fail-soft and LangChain4j 1.0.1 purity

## Workflow

1. Confirm CWD `<repo>`.
2. Refresh inventory without dumping secrets:
   - `ollama ls`
   - env name presence/length only (see spec)
   - optional: `. ./scripts/use_project_keys.ps1`
3. Choose route from `configs/api-routing.yaml` for the purpose (`search|asr|embed|llm`).
4. Apply alias map for installed Ollama models (`qwen3:8b` → `qwen3.5:9b`, etc.).
5. Patch the listed seam class; do not add a parallel provider stack.
6. Ensure debug logs use `ApiRoutingDebug` / `SafeRedactor` / `TraceStore` with `keyPresent` + `keySource` names only.
7. On 401/403/404/429/timeout/empty: set `errorClass` and fall back to next tier in the YAML list.

## Do not

- Print or commit API key values.
- Feed cloud keys into local Ollama as OpenAI keys.
- Assume port `11435` is up without a live probe.
- Change embedding dimensions or vector indexes unless explicitly requested.
- Delete existing env keys or provider settings.

## Official contracts (2026-09-17)

- Read `docs/API_ROUTING_SPEC.md` § Official cross-check before changing providers.
- Brave auth header is `X-Subscription-Token` (not Bearer).
- Groq chat/STT are OpenAI-compat under `https://api.groq.com/openai/v1` — `openai/gpt-oss-120b` is a Groq model id, not OpenAI.
- Soniox realtime model SoT is `stt-rt-v5` (v4 aliases to v5 until 2026-06-30).
- OpenAI lanes listing `gpt-5.5` / `gpt-5.4-mini`: **evidence needed** — do not enable without live `/v1/models`.
- Never treat `agent-prompts/*` session logs or dollar/minute prices as API specs.
- `app/.../configs/models.manifest.yaml` (2025-09-30 OpenRouter) is **not** SoT; prefer `configs/api-routing.yaml` + this skill.

## MODEL LOCK (Display + RAG)
- Never select `qwen3:8b` (dead). Fast/light/cheap = `qwen3.5:9b`.
- Never select `gemma3:*`, `qwen3:30b`, `qwen3-coder:*`, `qwen2.5:7b-instruct` as live defaults.
- Role map: chat=`gemma4:26b`, judge/coder=`smtek/Qwen3.8-27B:Q3_K_XL`, vision=`qwen3-vl:8b`, embed=`qwen3-embedding:4b`.
- See root `AGENTS.md` section **Ollama Model Lock** and `scripts/check-model-lock.ps1`.

## Grok Bot sync 2026-10-03

- (R13) 에이전트 작업 비용 순서(2026-10-03 확정, 화력 위주): Codex 크레딧 →
  외부 유료 API → 무료 티어 → 로컬 Ollama 맨 마지막 예외. "무료·로컬 우선"은
  폐기된 규칙이다. 에이전트 세션 상세는 `demo1-agent-api-spend-guard`.
- (R13) 제품 런타임 라우팅(`configs/api-routing.yaml` `policy.order`,
  `application-llm.yaml`)은 2026-10-02 기준 아직 옛 local-first 순서를
  담고 있다. 그것을 바꾸는 것은 Codex 제품 소스 작업(Plan9) — 룰·도구
  세션에서 패치하지 말고 `FOR_CODEX`로 남긴다.
- (R13) 무료 티어라 부르기 전에 분당·일일 한도, 재시도 이중 과금, 소진 시
  유료/기본 키로의 자동 폴백 여부를 확인한다.