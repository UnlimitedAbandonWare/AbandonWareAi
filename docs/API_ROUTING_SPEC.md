# API Routing Spec (demo-1)

> Snapshot date: 2026-09-18 (KST) · Machine: DESKTOP-M5NOV6K · Root: `C:\AbandonWare\demo-1\demo-1\src`
> **Never put secret values in this file, logs, skills, PRs, or chat.** Reference env var **names** only.

## Purpose

Codex / agents / humans must read this **before** adding search, RAG, transcription, embedding, or LLM calls.

Policy order (always):

1. Prefer APIs already present in this environment (env / `.secrets/providers.json` / Spring props).
2. Prefer **free / local** first, then **low-cost**, then **paid higher quality** only when needed.
3. Prefer **Ollama** for chat / embed / vision when quality is acceptable.
4. Do **not** invent new providers or hardcode keys.
5. On failure (0 calls, 401/403/404/429, quota, timeout), log **reason codes + status + provider + endpoint class** — never keys.

Machine-readable twin: `configs/api-routing.yaml`.
Agent skill: `.agents/skills/demo1-api-routing-inventory/`.
Related existing skills: `$demo1-local-llm-gpu-gateway`, `$demo1-rag-platform`.

---

## Live credential presence (names only)

Observed on Desktop User/Process env and/or `.secrets/providers.json` / `.env`+`shared.env` (hard-linked).

| Env var | Process/User env | Notes |
|---|---|---|
| `BRAVE_API_KEY_FREE` | operator-assigned free lane | Brave free plan; monthly local quota then promote to base |
| `BRAVE_API_KEY` | set | Brave base lane (`gpt-search.hybrid.primary: BRAVE`) |
| `BRAVE_SUBSCRIPTION_TOKEN` | retired | Do not read. Leftover env is unused. Auth header remains `X-Subscription-Token` |
| `TAVILY_API_KEY` | set | Web search |
| `SERPAPI_API_KEY` | set | Web search |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` / `NAVER_KEYS` | in providers store | Search; process env may be empty until `use_project_keys.ps1` |
| `KAKAO_REST_API_KEY` | in providers store | Places / maps style integrations |
| `DEEPGRAM_API_KEY` (+ `_SECONDARY`) | set | STT |
| `SONIOX_API_KEY` | set | STT realtime (`SONIOX_STT_MODEL=stt-rt-v5`, region `us`) |
| `SONIOX_STT_ENABLED` | true (store) | Explicit opt-in |
| `OPENAI_API_KEY` | set | Paid LLM / embedding fallback |
| `GROQ_API_KEY` | set | Cheap/fast cloud LLM |
| `GEMINI_API_KEY` | set | Cloud LLM |
| `ANTHROPIC_API_KEY` | unset in process; present in store | Use only if loaded into env |
| `OPENCODE_API_KEY` / `OPENCODE_GO_API_KEY` | in store | OpenCode Zen |
| `AI_GATEWAY_API_KEY` | in store | Gateway |
| `ZAI_API_KEY` | in store | Z.ai / GLM family |
| `PINECONE_API_KEY` | set | Vector |
| `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN` | set | Cache / rate limit |
| `UPSTASH_VECTOR_API_KEY` | in store | Vector (also `UPSTASH_VECTOR_URL` / `UPSTASH_VECTOR_TOKEN` props) |
| `OLLAMA_HOST` / `OLLAMA_BASE_URL` | unset | App uses `LLM_BASE_URL` / defaults `127.0.0.1:11434|11435` |
| `OPENROUTER_API_KEY` | unset | Manifest mentions; not live |

Loader: `. ./scripts/use_project_keys.ps1` (prints **names**, not values). Canonical file: `.env` ≡ `shared.env` (see `ENV_CONFIG_README.md`).

---

## Ollama (installed 2026-09-18 KST — live SoT)

`ollama ls` on DESKTOP-M5NOV6K:

| NAME | SIZE | Role |
|---|---|---|
| `smtek/Qwen3.8-27B:Q3_K_XL` | 14 GB | **Preferred judge/coder** (VRAM-tight vs 17GB full) |
| `qwen3.8:27b` | 17 GB | Judge/coder fallback / strong chat |
| `qwen3.6:27b` | 17 GB | Judge/chat alt |
| `gemma4:31b` | 19 GB | High-quality chat / judge alt |
| `gemma4:26b` | 17 GB | **Default chat** |
| `gemma4:latest` | 9.6 GB | Chat mid |
| `gemma4:12b` | 7.6 GB | Fast/chat mid |
| `qwen3.5:9b` | 6.6 GB | **Default fast/light/hints** |
| `qwen3-vl:8b` | 6.1 GB | **Vision** |
| `qwen3-embedding:4b` | 2.5 GB | **Preferred embed** (lighter) |
| `qwen3-embedding:latest` | 4.7 GB | Embed |
| `nomic-embed-text:latest` | 274 MB | Embed alt |
| `bge-m3:latest` | 1.2 GB | Embed alt |

**Role defaults (Spring / routing):** fast=`qwen3.5:9b` · chat=`gemma4:26b` · judge/coder=`smtek/Qwen3.8-27B:Q3_K_XL` · vision=`qwen3-vl:8b` · embed=`qwen3-embedding:4b`.

**Alias map (dead tag → installed; alias-only, never Spring default):**

| Dead / stale config string | Prefer installed |
|---|---|
| `qwen3:8b` | `qwen3.5:9b` |
| `qwen3:30b` / `qwen3-coder:30b` | `smtek/Qwen3.8-27B:Q3_K_XL` (then `qwen3.8:27b`) |
| `gemma3:27b` | `gemma4:26b` |
| `gemma3:4b` | `gemma4:12b` |
| `qwen2.5:7b-instruct` / `qwen2.5-7b-instruct` | `qwen3.5:9b` |
| `qwen3-embedding` (untagged) | `qwen3-embedding:4b` |

Ports (from existing docs / gradle homes): **11434** (common), **11435** / **11437** (dual-GPU / embed lanes). Probe live before assuming 11435.

OpenAI-compat base: `http://127.0.0.1:<port>/v1` · Native embed: `http://127.0.0.1:<port>/api/embed`.

---

## Providers by purpose

### 1) Web search / RAG retrieval

| Priority | Provider | Env | Base / auth | Cost | Fallback |
|---|---|---|---|---|---|
| 1 | Brave | `BRAVE_API_KEY_FREE` then `BRAVE_API_KEY` | `https://api.search.brave.com/res/v1/web/search` · `X-Subscription-Token` (header kept; env `BRAVE_SUBSCRIPTION_TOKEN` retired) | Free key then same-host base | Tavily → SerpAPI → Naver |
| 2 | Tavily | `TAVILY_API_KEY` | Tavily Search API · Bearer/key header per client | Dev/cheap | SerpAPI |
| 3 | SerpAPI | `SERPAPI_API_KEY` | `https://serpapi.com/search` | Paid | Naver |
| 4 | Naver | `NAVER_KEYS` or client id/secret | Naver Search Open API | Free-ish quota | disable fail-soft |

**Code seams:** `BraveSearchService`, `BraveSearchProvider`, `NaverSearchService`, `TavilyWebSearchRetriever`, `UnifiedRagOrchestrator`. Config: `gpt-search.hybrid.primary: BRAVE`.

### 2) Transcription (ASR)

| Priority | Provider | Env | Endpoint class | Notes | Fallback |
|---|---|---|---|---|---|
| 1 | Soniox | `SONIOX_API_KEY`, `SONIOX_STT_*` | Regional WS + Node sidecar | `stt-rt-v5`, region `us` | Java Soniox → Deepgram |
| 2 | Deepgram | `DEEPGRAM_API_KEY` (+ secondary) | Streaming WS listen | PCM16 mono; no auto key swap | fail soft |

**Code seams:** `SonioxSidecarManager`, `SonioxSttService`, `DeepgramSttService`, `ConversateCloudStt`. Docs: `docs/soniox-sidecar.md`, `docs/deepgram-stt.md`.

### 3) Embeddings / vector

| Priority | Provider | Env / props | Endpoint | Fallback |
|---|---|---|---|---|
| 1 | Ollama embed | local | `11434`/`11435` `/api/embed` · model `qwen3-embedding:4b` (then `:latest`) | port failover 11435↔11434 |
| 2 | OpenAI embed | `OPENAI_API_KEY` | `text-embedding-3-small` | after local fast-fail |
| 3 | Pinecone / Upstash | `PINECONE_*`, `UPSTASH_VECTOR_*` | vector store | other store |

**Code seams:** `OllamaEmbeddingModel`, `DecoratingEmbeddingModel`, Matryoshka normalizer, `GET /api/diagnostics/embedding`. Doc: `docs/EMBEDDING_FAILOVER_ADVANCED.md`.

### 4) LLM chat / tools / vision / hints

| Priority | Lane | Model preference | Base | Auth |
|---|---|---|---|---|
| 1 free/local | chat high | `gemma4:26b` / `gemma4:31b` | `LLM_BASE_URL` or `http://127.0.0.1:11434/v1` | `LLM_API_KEY` placeholder `ollama` |
| 1 free/local | fast / hints | `qwen3.5:9b` | `LLM_FAST_BASE_URL` | same |
| 1 free/local | vision | `qwen3-vl:8b` | vision base | same |
| 1 free/local | judge / coder | `smtek/Qwen3.8-27B:Q3_K_XL` (then `qwen3.8:27b`) | high base | same |
| 2 cheap cloud | Groq | Llama-class via Groq | `https://api.groq.com/openai/v1` | `GROQ_API_KEY` |
| 2 cheap cloud | Gemini | Gemini models | Google AI | `GEMINI_API_KEY` |
| 2 cheap cloud | Z.ai / OpenCode | per store | provider docs | `ZAI_API_KEY` / `OPENCODE_API_KEY` |
| 3 quality cloud | OpenAI | `OPENAI_API_MODEL` / gpt-* | `https://api.openai.com/v1` | `OPENAI_API_KEY` |
| 3 quality cloud | Anthropic | Claude | Anthropic API | `ANTHROPIC_API_KEY` (if env-loaded) |

**Code seams:** `LlmRouterAspect`, `LlmRouterBandit`, `OpenAiChatModel`, `OllamaNativeChatModel`, `KeyResolver`, `ProviderGuardConfig`, `main/resources/application-llm.yaml`, `configs/models.manifest.yaml`.

**Display cue exception:** the priority order above governs the general chat/RAG lanes. Meta Display cue/hint generation is a separate policy surface — `ConversateCueRoutingPolicy` selects only the configured cloud routes in `application-meta-display.yml` (`conversate.cue.routes.*`: gpt-5.6-luna/terra/sol, gemini-3.8-flash, gemini-3.5-flash-lite, guarded Groq fallbacks) and excludes `local()` candidates; the local model participates only via the explicit `conversate.cue.local-support-enabled` fallback. Both orders are intentional — do not merge them or "align" one to the other.

`ProviderGuard` requires `llm.provider=local` when `require-local` is on. External keys must not be fed into local Ollama as if they were OpenAI.

---

## Failure / debug contract

At every routing decision and HTTP/WS outcome, log structured fields **without secrets**:

- `purpose` = search|asr|embed|llm|vision|hint
- `provider`, `model` (or `n/a`)
- `endpointClass` = host+path template (no query tokens)
- `attempt`, `httpStatus` or `wsCloseCode`
- `errorClass` = missing_key|unauthorized|forbidden|not_found|rate_limited|quota|timeout|empty_result|network|disabled
- `fallbackTo` (next provider id or `none`)
- `keyPresent` boolean, `keySource` env/prop **name** only, `keyLen` optional

Use existing helpers: `SafeRedactor`, `TraceStore`, `[ProviderGuard]` log prefix, `ApiRoutingDebug` (this change).

Never log: raw keys, Authorization headers, PCM audio, full prompts, or provider error bodies that echo credentials.

---

## How agents should use this

1. Open `docs/API_ROUTING_SPEC.md` and `configs/api-routing.yaml`.
2. Run `ollama ls` and confirm ports with `/api/tags` (loopback only).
3. Confirm env **names** present (`use_project_keys.ps1` or process env) — do not dump values.
4. Pick lowest-cost capable route from the purpose table; escalate only on quality need or repeated local failure.
5. Patch existing seams (`KeyResolver`, search services, Conversate ASR, embedding model) — do not add parallel stacks.
6. Keep LangChain4j at `1.0.1` purity (see `$demo1-rag-platform`).

---

## Inventory refresh command

```powershell
ollama ls
# names/lengths only — never print values
@(
  'BRAVE_API_KEY','TAVILY_API_KEY','SERPAPI_API_KEY','OPENAI_API_KEY','GROQ_API_KEY','GEMINI_API_KEY',
  'DEEPGRAM_API_KEY','SONIOX_API_KEY','PINECONE_API_KEY','UPSTASH_REDIS_REST_TOKEN','LLM_BASE_URL','OLLAMA_HOST'
) | ForEach-Object {
  $v = [Environment]::GetEnvironmentVariable($_,'Process')
  if (-not $v) { $v = [Environment]::GetEnvironmentVariable($_,'User') }
  if ([string]::IsNullOrEmpty($v)) { "$_ unset" } else { "$_ set len=$($v.Length)" }
}
```
