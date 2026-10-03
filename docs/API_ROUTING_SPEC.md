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
Provider limits SSOT (public docs, account details unknown): `docs/provider-limits/README.md`.
Related existing skills: `$demo1-local-llm-gpu-gateway`, `$demo1-rag-platform`.

### Accepted run, transport, and resource policy (2026-10-03)

The core accepted-execution budget split and Responses transport policy are
applied and covered by focused tests. Live verification, SDK-specific timeout
semantics, durable owner-loss handling, and the unapplied browser proposal are
tracked in `data/agent-handoff/codex-timeout-split-ae8f6e83/report.md`.
The migration contract below does not substitute for acceptance evidence.

| Lifetime | Contract | Protections retained |
|---|---|---|
| Accepted logical chat/task run | Completion, classified provider failure, or explicit cancellation determines the outcome. Elapsed wall time, delayed ACK, browser refresh, and transport detach do not terminate the run or authorize another attempt. | Exact run identity, cancellation/commit fencing, one final outcome, bounded retry count. |
| Ingress and transport | Apply finite limits to request-body reception, DNS/connect/TLS, pool acquisition, response headers, and time since the last read/progress event. SSE heartbeat/delta renews read-stall observation. A connection timeout detaches the transport and permits the same run to reattach. | Body size, phase deadlines, transport failure classification, connection cleanup. HTTP 408 applies only to incomplete request reception. |
| Resources and retention | Bound workers, queue depth, concurrency, tokens, cost, tool calls, and retry attempts. Retain completed results for a finite TTL. A running worker is not a terminal record to expire. | Existing bounded executors and overload rejection, terminal-only TTL, bounded shutdown drain, positive owner-loss fencing. |

Fallback requires a classified provider/transport failure and physical worker
exit or established fencing. `Future.cancel` acceptance or wrapper `isDone`
does not prove that the provider worker has exited. Explicit cancellation
causes remain distinct: `user_stop`, `api_cancel`, `session_delete`, and
`shutdown`. `transport_detach` is a connection event, not logical cancellation.

The imported `application-llm.yaml` remains the sole YAML source for
`public.request-budget.max-time-budget-ms`; `PUBLIC_REQUEST_MAX_TIME_BUDGET_MS`
still takes precedence. Preserve the existing key and value while moving its
application to ingress admission/body reception. The rendered legacy header
must not become an accepted-run deadline. No unlimited-wait sentinel value is
introduced by this contract.

Compatibility mapping (source migration; live verification tracked in the task report):

| Existing key | Intended purpose after migration | Status |
|---|---|---|
| `public.request-budget.max-time-budget-ms` | Bounded ingress/body reception; preserve existing env override. | Body reception remains finite; accepted chat/task execution hides the ingress budget. |
| `llm.timeout-seconds`, tier timeout keys, requested-model timeout key | Preserve names while separating route selection from provider transport phase policy. | Accepted execution bypasses total-wait caps; positive values remain transport policies. SDK timeout semantics outside Responses are not yet observed. |
| `spring.mvc.async.request-timeout` | Lifetime of the MVC connection; detach without cancelling accepted work. | Viewer detach preserves the run; ACK/disconnect regressions cover replay. |
| `chat.resume.inflight-idle-timeout-seconds` | Age diagnostic and owner-loss investigation; no age-only terminalization. | Nonterminal age is diagnostic; positive replacement-owner evidence fences old ownership. Store unavailability alone does not cancel. |
| `chat.resume.ttl-seconds` | Retention of terminal runs only. | Retained. |
| `RAG_BACKEND_TIMEOUT_MS` | BFF transport protection with `transport_timeout`; user Stop uses `user_abort`. | Header wait uses transport_timeout; caller abort uses user_abort. Accepted paths keep finite header protection; ordinary paths retain 15 seconds. |

Accepted chat and synchronous, persisted, and development task workers bind
an explicit execution scope. Context propagation retains this scope separately
from the finite ingress TimeBudget. Bounded search/tool waits remain fail-soft.

The current chat.js viewer timer still calls the same cancel API as user Stop.
The server cannot infer intent from identical requests. The task stores a
detach/reconnect proposal without applying it; full end-to-end
Stop-only cancellation remains pending separately authorized UI work.

Configured model IDs keep their existing provider/GPU endpoints. Preferred
selection and strict selection retain their existing eligibility rules.
Disabled/missing-key providers remain disabled; authenticating or changing
credentials is outside this migration.

Primary references checked 2026-10-03: [Java 17 Future](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html),
[Reactor Netty 1.1 HttpClient responseTimeout](https://projectreactor.io/docs/netty/1.1.11/api/reactor/netty/http/client/HttpClient.html),
and [Spring MVC asynchronous processing](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html).
The Spring 6.2 page supports the transport distinction only; the project remains
Spring Boot 3.3.4 / Spring Framework 6.1 and requires its own focused tests.

---

## Live credential presence (names only)

Observed on Desktop User/Process env and/or `.secrets/providers.json` / `.env`+`shared.env` (hard-linked).

| Env var | Process/User env | Notes |
|---|---|---|
| `BRAVE_API_KEY_FREE` | operator-assigned free lane | Brave free plan; on quota exhaustion (402/429) auto-promote to `BRAVE_API_KEY` — pre-approved 2026-09-30, no per-call ask |
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

- ChatGPT OAuth lane: token file `.secrets/chatgpt_oauth_credentials.json` (env `CHATGPT_OAUTH_CREDENTIALS`); `python -B scripts/chatgpt_oauth_flow.py test-smoke` is the recognized live E2E check (exit 0 = valid token, real call).
- Agent session spend order (SSOT `configs/agent-api-spend-guard.yaml` + `$demo1-agent-api-spend-guard`, 2026-10-03): `codex_credits → external_paid_api → free_tier → local_ollama`, capability-first. Paid calls are ON by default for agent sessions — `AWX_AGENT_ALLOW_PAID_MODELS` is a kill switch (`='0'`/`false`/`no`/`off` blocks); `62,500` ChatGPT-plan credits authorized 2026-09-30 — allocation input, not observed balance; Vercel AI Gateway USD is a separate budget): `$env:AWX_CREDIT_BUDGET='62500'; $env:AWX_AGENT_SESSION='<taskId>'` — warn-log at 50,000 cumulative, hard bound stays the authorized budget. `AWX_AGENT_SESSION` is required by the Jev gateway spend ledger (`scripts/apikit/jev_ledger.py`); without it a live gateway send is refused `budget_refused:session-missing`.

---

## Ollama (installed 2026-09-18 KST — live SoT)

`ollama ls` on DESKTOP-M5NOV6K:

| NAME | SIZE | Role |
|---|---|---|
| `smtek/Qwen3.8-27B:Q3_K_XL` | 14 GB | **Preferred judge/coder** (VRAM-tight) |
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
| `qwen3:30b` / `qwen3-coder:30b` | `smtek/Qwen3.8-27B:Q3_K_XL` |
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
| 1 | Brave | `BRAVE_API_KEY_FREE` then `BRAVE_API_KEY` | `https://api.search.brave.com/res/v1/web/search` · `X-Subscription-Token` (header kept; env `BRAVE_SUBSCRIPTION_TOKEN` retired) | Free key; 402/429 auto-promote to base (pre-approved 2026-09-30) | Tavily → SerpAPI → Naver |
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
| 1 free/local | judge / coder | `smtek/Qwen3.8-27B:Q3_K_XL` | high base | same |
| 2 cheap cloud | Groq | Llama-class via Groq | `https://api.groq.com/openai/v1` | `GROQ_API_KEY` |
| 2 cheap cloud | Gemini | Gemini models | Google AI | `GEMINI_API_KEY` |
| 2 cheap cloud | Z.ai / OpenCode | per store | provider docs | `ZAI_API_KEY` / `OPENCODE_API_KEY` |
| 3 quality cloud | OpenAI | `OPENAI_API_MODEL` / gpt-* | `https://api.openai.com/v1` | `OPENAI_API_KEY` |
| 3 quality cloud | Anthropic | Claude | Anthropic API | `ANTHROPIC_API_KEY` (if env-loaded) |

**Code seams:** `LlmRouterAspect`, `LlmRouterBandit`, `OpenAiChatModel`, `OllamaNativeChatModel`, `KeyResolver`, `ProviderGuardConfig`, `main/resources/application-llm.yaml`, `configs/models.manifest.yaml`.

**Display cue exception:** the priority order above governs the general chat/RAG lanes. Meta Display cue/hint generation is a separate policy surface — `ConversateCueRoutingPolicy` selects only the configured cloud routes in `application-meta-display.yml` (`conversate.cue.routes.*`: gpt-5.6-luna/terra/sol, gemini-3.8-flash, gemini-3.5-flash-lite, guarded Groq fallbacks) and excludes `local()` candidates; the local model participates only via the explicit `conversate.cue.local-support-enabled` fallback. Both orders are intentional — do not merge them or "align" one to the other.

`ProviderGuard` requires `llm.provider=local` when `require-local` is on. External keys must not be fed into local Ollama as if they were OpenAI.

### 5) Model install / route registration (2026-09-27, design+thin skeleton)

| Purpose | Tier | Route / seam | Registration path |
|---|---|---|---|
| Local model install | free/local | Managed Ollama `/api/pull` via `LocalLlmProcessManager` | `POST /api/chat/models/install {target:"local", model}` |
| Cloud route registration | low_cost first; paid only when named | `llmrouter.models.<key>` + manifest `enabled` + `remote-model-selection-routes` | `POST /api/chat/models/install {target:"cloud", route}` |

- Allowlist strategy: **approved routeKey add only** — never a blind global
  `app.ai.allow-remote-model-selection=true` (that would also open
  `openai-economy` and any other enabled route; observed 2026-09-27).
- Registration precondition = existing probe verdict: manifest↔route match,
  `enabled`, credential env present (names only), Groq guard clean. Registered
  route becomes selectable only via catalog refresh (cache expire).
- Runtime allowlist is in-memory; persistence across restart is the operator
  step (`CHAT_REMOTE_MODEL_SELECTION_ROUTES` / properties) — documented, not
  automated.
- Meta Display cue lane stays separate: `conversate.cue.routes.*` /
  `ConversateCueRoutingPolicy` is untouched; web install changes only the main
  chat picker surface.
- Details: `agent-prompts/devin-api-routing-web-install-skeleton-20260927/`
  (`INVENTORY-WP0.md`, `DESIGN-WP1.md`).

### 6) Vercel AI Gateway — Jev evaluation (ZDR rule SSOT)

- **ZDR default OFF.** Do not attach `zeroDataRetention` / `zero_data_retention` / `zero-data-retention` to Vercel AI Gateway requests by default. The team is on the **Hobby** plan and a ZDR request is rejected with HTTP 403 plan-entitlement error (`plan_gate`). Enable only on **Pro+ plans** via explicit config (`jev.gateway.zero-data-retention`, default `false`); never enable the dashboard team-ZDR toggle.
- Reason vocabulary (smoke + product code): `401`→`auth_invalid`; `403` + plan/Pro/ZDR wording→`plan_gate`; other `403`→`permission_denied`; `402`→`billing-blocked`; `429`→`rate_limited`; `5xx`→`upstream_error`. Do not collapse 401/403 into `auth-blocked`.
- Smoke: `scripts/jev_gateway_smoke.mjs` (delegated once by `scripts/jev_api_smoke.py --live`) reports `zdr:"off"`; one bounded call per run. Credential `AI_GATEWAY_API_KEY` resolves `.secrets/providers.json` first (same precedence as `use_project_keys.ps1` non-`-Runtime`); report key len/sha8/src only, never the value.
- Recurrence guard: `python -B scripts/zdr_guard.py` fails when a ZDR flag is hardcoded `true` without a config gate (stdlib, no Java/Gradle). Exemption: a file-level `zdr-guard: allow-file` marker (e.g. loopback mock fixtures).
- App budget knobs are report-only, not changed by agents: `demo.jev.mode=off`, `demo.jev.free-only=true`, `demo.jev.allow-paid=false` in `main/resources/application-meta-display.yml`; `JevDecisionAdvisor` may return `budget_skip` even when the live gateway answers 200.
- Product Java seam (`JevGatewayClient.java`, `JevGatewayClientTest.java`) is Codex-owned — handoff: `toss/TOSS-01.md`.
- Optional daily admission in `JevDecisionAdvisor`: `demo.jev.budget.enabled=false`, `demo.jev.budget.daily-max-calls=0` (Java defaults). When enabled, 0 blocks all calls with `budget_skip`; the process-memory counter counts dispatch attempts per UTC day, including failures/timeouts, refunds rejected submissions only, and resets on process restart. This only narrows existing admission; it is not a USD or multi-JVM ledger.

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

#### External API failure classification (SSOT — 2026-09-29)

Canonical vocabulary = `scripts/apikit/common.py` `ALL_CLASSES`. Every agent
report on an external provider API call **starts with** the evidence row
`provider | http | classification | key sha8 | cost | evidence`. A mock or
fixture pass is `NOT_RUN(실제 확인 안 함)` for real-provider verification.
The single check entry point is `python -m scripts.apikit check` (free only;
`call --paid` is explicitly paid). Never collapse several statuses into
`auth-blocked` — that label is the **local-server** verdict meaning
"server alive, login required" (`scripts/debug_rag_stack.ps1`) only.

| Signal | Canonical class | Smoke/product alias | Rule |
| --- | --- | --- | --- |
| key env absent everywhere | `KEY_MISSING` | `missing-env` | value never printed; report srcs/len/sha8 |
| sources disagree (Process/User/Machine/.secrets) | `KEY_MISMATCH` | — | fix sources before any verdict |
| HTTP 401 | `KEY_INVALID_OR_EXPIRED` | `auth-invalid`, `auth-blocked` | invalid/revoked/expired key; check `configs/api-key-expiry.json` |
| HTTP 403 + plan/paid-feature wording (`upgrade your plan`, `current plan`, `only available for Pro…`) | `PLAN_GATE` | `plan_gate` | plan restriction — Hobby must not request Pro-only flags (e.g. `zeroDataRetention`) |
| HTTP 403 other | `FORBIDDEN_REGION_OR_IP` | `permission_denied`, `auth-blocked` | permission/region/IP denial — needs body/code evidence, never assumed |
| HTTP 402 | `QUOTA_OR_BALANCE` | `billing-blocked` | balance/billing, not auth |
| HTTP 404 when provider confirms the model absent/retired | `MODEL_NOT_FOUND` | `not_found` | only with list/lookup evidence; otherwise `UNKNOWN` |
| HTTP 429 | `RATE_LIMIT` | `rate_limited` | unless body explicitly says quota → `QUOTA_OR_BALANCE` |
| HTTP 400 | `BAD_REQUEST_SHAPE` | — | request schema, not auth |
| timeout/DNS/TLS/conn refused | `NETWORK` | `timeout`, `network` | no HTTP evidence at all |
| HTTP 5xx | `SERVER_5XX` | `upstream_error` | provider-side |
| unparseable / unmapped | `UNKNOWN` | — | keep `response_snippet` (masked) |

- Key expiry ledger `configs/api-key-expiry.json` stores **metadata only**
  (`env`, `sha8`, `last4`, `expiresAt`): D-7 → `key_expiry=warn-dN`, past →
  `expired`, sha8 mismatch → `other_key`, missing metadata → `not_recorded`
  (never guessed). Key **values** stay in `.secrets/providers.json` / env only.
- Hobby-plan rule: requests, examples, prompts and test fixtures must not
  include Pro-only provider options (observed 2026-09-29: `zeroDataRetention`
  → 403 `plan_gate`, fixture `scripts/apikit/fixtures/vercel_403_zdr_plan.json`).
- Same-key mixed results (401 then 403 minutes apart) are a stale-scope or
  key-mismatch signal — record `key_srcs`/`key_sha8` per row before concluding.

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
