# LLM API 소스수정 지시서

- 작성일: 2026-09-19
- 작업 루트: `C:\AbandonWare\demo-1\demo-1\src`
- 이번 세션: **소스 수정 없음**. 현재 로컬 파일 + 실제 빌드/실행 경로 + 공식 문서를 SSOT로 조사한 실행용 지시서.
- Git/GitHub 과거 상태는 사용하지 않음.
- 비밀값: API key/token/secret **미출력**. 환경변수는 이름·존재 여부만.

판정 원칙: 이 제품의 핵심은 긴 일반 챗이 아니라 **실시간 STT → (필요 시) 검색/RAG → 짧고 완결된 힌트 → Meta Ray-Ban Display**. 모델 벤치마크 순위보다 지연시간, 첫 토큰, 짧은 한국어 품질, 빠른 fallback, API 비용, 검색 조화, 단일 provider 사망 시 전체 힌트 경로 생존을 우선한다. Provider를 늘리는 방향은 기본값이 아니다.

교차검증 한 바퀴만 수행했다. 역할 합의는 증거가 아니다.

---

## A. 현재 실제 구조

### A.1 활성 소스셋 (런타임 SSOT)

Gradle 증거 (`build.gradle.kts` sourceSets, `settings.gradle`):

| 경로 | 상태 |
|---|---|
| `main/java`, `main/resources` | **CURRENT_CANONICAL** (root runtime) |
| `app/src/main/java_clean`, `app/src/main/resources` | `:app` owner. LLM provider 구현 없음 |
| `app/src/main/java`, `lms-core`, nested `demo-1/`, backups | 비활성/참고. `LlmRouterService` 등 여기 있으면 **미사용** |

LangChain4j 순도: `dev.langchain4j:langchain4j:1.0.1` + `langchain4j-open-ai:1.0.1`만. Ollama/Anthropic/Google 전용 LC4j 모듈 없음. `resolutionStrategy`가 다른 버전을 1.0.1로 고정.

기본 Spring profile: `local`. 실제 Display/RAG 런처 `Start-RAG.bat` → `scripts/start_rag_stack.ps1 -MetaDisplay`는 **`local,meta-display`**. `application.yml`이 `application-llm.yaml`을 항상 import.

2026-09-19 로컬 관측:

- `ollama ls`: `gemma4:26b`, `qwen3.5:9b`, `smtek/Qwen3.8-27B:Q3_K_XL`, `qwen3-vl:8b`, `qwen3-embedding:4b` 등 `configs/api-routing.yaml` allowlist와 일치.
- `127.0.0.1:11434` / `11435` `/api/version` 응답 있음.
- `18180` Spring: **not_observed** (이 세션에서 서버를 켜지 않음).
- env 이름 존재: `OPENAI_API_KEY`, `GROQ_API_KEY`, `GEMINI_API_KEY`, `BRAVE_API_KEY`, `DEEPGRAM_API_KEY`, `SONIOX_API_KEY`, `ZAI_API_KEY`, `OPENCODE_API_KEY` = set. `ANTHROPIC_API_KEY`, `XAI_API_KEY`, `MOONSHOT_API_KEY`, `OPENROUTER_API_KEY`, `LLM_BASE_URL`, `LLM_CHAT_MODEL`, `OLLAMA_HOST` = unset.

### A.2 실제 사용 provider (경로별로 다름)

두 개의 서로 다른 LLM 계약이 동시에 살아 있다. **하나를 다른 쪽에 덮어쓰면 안 된다.**

#### 경로 1 — Meta Ray-Ban Display 힌트 (핵심 제품 경로) — **클라우드 우선, 로컬 제외**

런타임 사용:

| 우선 | route key | model | provider | 역할 |
|---|---|---|---|---|
| quality 1 / gate | `gemini-cue` | `gemini-3.5-flash-lite` | gemini | 싼 1차 힌트 |
| quality 1 / gate | `groq-gate` | `openai/gpt-oss-20b` | groq | 무료 티어만 |
| quality 1 / gate | `openai-economy` | `gpt-5.6-luna` | openai | 경제 fallback |
| quality 3 | `api3` | `openai/gpt-oss-120b` | groq | 강한 Groq (weight 0, fallback) |
| quality 3 | `gemini-pro` | `gemini-3.8-flash` | gemini | 고품질 |
| quality 3 | `openai-balanced` | `gpt-5.6-terra` | openai | 중간 |
| quality 4 | `openai-premium` | `gpt-5.6-sol` | openai | 희귀 |

근거: `main/resources/application-meta-display.yml` L57–169, `ConversateCueRoutingPolicy.local()`이 `local|ollama|local-openai`를 **후보에서 삭제**.

선택 지표: 가격 × EWMA latency / successRate. OpenAI/Gemini를 primary로 선호. 실패 시 20s/60s/300s cooldown. Groq는 `GroqFreeTierGuard` + `daily-budget-usd: 0` (유료 Groq 금지).

로컬 `qwen3.5:9b` (`llm.fast` / `llmrouter.models.light`): **이 경로에서 정의되어 있으나 미사용**. `ConversateLocalCardGenerator`는 클라우드 전부 실패 후 제안용이며 `conversate.generation.enabled` 기본은 꺼져 있다.

#### 경로 2 — 일반 RAG/채팅 (`ChatService` → `ChatWorkflow`) — **로컬 우선**

런타임 사용:

- Provider: `llm.provider=local` (Ollama OpenAI-compat `/v1`).
- chat: `gemma4:26b` (11434 또는 `LLM_BASE_URL` 기본 11435).
- fast: `qwen3.5:9b`.
- judge/coder: `smtek/Qwen3.8-27B:Q3_K_XL`.
- `llmrouter.auto` UCB1은 **weight>0**만 (`light` 0.45, `gemma` 0.55). 클라우드 라우트는 `fallback-only` + weight 0 → auto 미선택.
- 로컬 실패 시 클라우드 fallback 키: `llm.gateway.cloud.route-key=api3` = Groq `openai/gpt-oss-120b`.

호출: `DynamicChatModelFactory.lcWithTimeout` ← `@Around LlmRouterAspect`. 기본 bean은 `LlmConfig.chatModel` (`OpenAiChatModel` LC4j).

`OllamaNativeChatModel`: loopback `qwen3.5:9b` / `qwen3:*` / `gemma4:12b` 등에 `think=false` native `/api/chat`. `gemma4:26b`는 `/v1/chat/completions`.

### A.3 Display 힌트 실제 호출 경로

```
Fold6/glasses PCM
  → DisplayConversateController.audioStart/chunk/stop
  → ConversateAsrBridge
      기본(Start-RAG): CONVERSATE_ASR_PROVIDER=local → 로컬 Python child
      미완성 utterance fallback: ConversateCloudStt.transcribeEconomy
        → Groq whisper-large-v3-turbo (free 검증 시)
        → 아니면 Gemini generateContent gemini-3.5-flash-lite
  → ConversateSessionService.submit (final / rolling delta / 180s force)
  → ConversateAnswerPipeline.answerPublicDisplay
  → ConversateApiCueService.answer          [cues 빈이 있으면 ChatService 분기는 실행 안 됨]
      1) ConversateQuestionPolicy.cueDecision  (로컬 규칙, LLM 게이트 아님)
         NO_CUE | CUE(FAST) | RAG_CUE
      2) RAG_CUE만 UnifiedRagOrchestrator.query (web only, vector/KG/self-ask off)
         → selectCueDocuments 최대 3 snippet
      3) ConversateCardPrompt.cueHint  (PromptBuilder 아님)
      4) ConversateCueRoutingPolicy.reserve
      5) LlmRouterAspect.apiAttempt(key, timeout, maxTokens, jsonSchema).chat(...)
         비스트리밍 Chat Completions + JSON schema
      6) 실패 시 다른 cloud route ≤3, 그다음 local-support (보통 disabled)
  → Session.card
  → poll /api/assist/display/poll 또는 /lens/text
  → receiver.js / lens.js  (612자, ~8줄/페이지, 20s TTL)
```

검색 결과가 LLM에 들어가는 방식: `ConversateCardPrompt.cueHint`의 `{transcript, recentContext, evidence:[{id,text}]}`. **`PromptBuilder.build(PromptContext)`는 이 경로에서 사용되지 않는다.** `cueGate` LLM 프롬프트는 **정의되어 있으나 미사용** (게이트는 `ConversateQuestionPolicy` 로컬 규칙).

타임아웃: total 12s, gate 3s, hint 6.5s, max-attempts-per-stage 3, max-output-tokens 640, 힌트 하드캡 612 문자.

스트리밍: 힌트 생성 **없음**. `ChatModel.chat()`. SSE는 Display 렌즈 경로가 아니라 인증 conversate 페이지.

### A.4 핵심 클래스 / 설정

| 파일 | 역할 | 런타임 |
|---|---|---|
| `main/java/com/example/lms/assist/ConversateApiCueService.java` | Display 힌트 owner | **사용** |
| `ConversateCueRoutingPolicy.java` | 비용/EWMA/쿨다운 후보 | **사용** |
| `ConversateQuestionPolicy.java` | FAST vs RAG_CUE | **사용** |
| `ConversateCardPrompt.java` | `cueHint` 사용, `cueGate` 미사용 | 혼합 |
| `ConversateAnswerPipeline.java` | cues 있으면 ChatService 우회 | **사용** |
| `ConversateSessionService.java` | STT→hint trigger | **사용** |
| `ConversateAsrBridge.java` / `ConversateCloudStt.java` | STT | **사용** (Display 기본은 local child) |
| `ConversateLocalCardGenerator.java` | 클라우드 실패 후 제안 | **조건부** (generation 기본 off) |
| `ai/.../LlmRouterAspect.java` | 라우터 + `apiAttempt` | **사용** |
| `ai/.../LlmRouterBandit.java` | UCB1 (일반 chat auto) | **사용** (힌트는 직접 key) |
| `com.example.lms.learning.gemini.GeminiGateway.java` | Gemini OpenAI-compat + CB | **사용** (힌트/스피치) |
| `ai/.../OpenAiResponsesChatModel.java` | `/v1/responses` 어댑터 | **조건부** (model-guard ROUTE_RESPONSES; 기본 모드는 SUBSTITUTE_CHAT) |
| `com.example.lms.llm.DynamicChatModelFactory.java` | 일반 chat 모델 생성 | **사용** (RAG chat) |
| `com.example.lms.llm.OllamaNativeChatModel.java` | native Ollama | **사용** (fast 레인) |
| `com.example.lms.guard.KeyResolver.java` | 키 이름 충돌 fail-closed | **사용** |
| `com.example.lms.service.ChatService.java` | 긴 RAG 챗 | **사용** (Display 힌트에서는 cues가 가로챔) |
| `main/resources/application-meta-display.yml` | Display cue 모델/가격 | **사용** (`Start-RAG`) |
| `main/resources/application-llm.yaml` | 로컬 역할 + 클라우드 기본(다수 disabled) | **사용** |
| `configs/api-routing.yaml` | 에이전트 라우팅 인벤토리 | 문서/가드 SSOT. **Display 힌트 선택 로직의 런타임 SSOT는 아님** |
| `main/resources/configs/cloud-models.manifest.yaml` | 2026-07-01 unverified | **SoT 아님** |
| `main/resources/configs/models.manifest.yaml` | 구 OpenRouter | **SoT 아님** |

### A.5 정의되어 있지만 런타임 미사용 / fail-closed

| 항목 | 근거 | 판정 |
|---|---|---|
| Anthropic / Claude native | `LlmRouterAspect.failUnsupportedProvider("unsupported_anthropic_native_route")`. `ANTHROPIC_API_KEY` unset | **미사용 (의도적 fail-closed)** |
| xAI / Grok chat | HTTP 클라이언트 없음. `XAI_API_KEY` unset. `GrokPromotionDiscovery`는 검색 브랜드, `grok-build-`는 OpenCode autolearn 이름 prefix | **미사용** |
| Kimi / Moonshot | HTTP 클라이언트 없음. `MOONSHOT_API_KEY` unset. `kimi-` prefix만 autolearn policy | **미사용** |
| OpenRouter | `OPENROUTER_API_KEY` unset, manifest disabled | **미사용** |
| llmrouter.external OpenCode | `enabled:false`. 키는 있으나 기본 꺼짐 | **정의·비활성** |
| GLM / Z.ai 힌트 경로 | `SubagentProviderConfiguration` `zai/glm-5.3-flash`는 `agent.subagent.glm.enabled` + `GLM_EXTERNAL_READY` + `AI_GATEWAY_API_KEY` 필요, 기본 false. Conversate 후보 아님 | **서브에이전트 전용, 힌트 미사용** |
| Mistral | `llmrouter.models.mistral-medium.enabled=false` | **비활성** |
| `ConversateCardPrompt.cueGate` | 정의만. 게이트는 로컬 규칙 | **미사용** |
| Display 힌트의 `qwen3.5:9b` | 로컬 제외 | **이 경로 미사용** |
| `ChatLanguageModel` (구 LC4j 이름) | 코드에 0건. 실제 타입은 `ChatModel` | **미사용** |
| nested `demo-1/` `LlmRouterService` | Gradle 제외 | **미사용** |
| `legacy.langchain4j-beans.enabled=false` | 명시 비활성 | **미사용** |

### A.6 오류·429·timeout·빈 응답

`ConversateApiCueService.call`:

- 빈/과장 출력 → `GENERATION_INVALID_OUTPUT`
- `TimeoutException` / budget → `GENERATION_TIMEOUT`
- 429 + `insufficient_quota|credit_balance_exhausted|spend_limit_exceeded` → `API_QUOTA_EXHAUSTED` (계정 5분 block)
- 그 외 429 → `GENERATION_RATE_LIMITED` + route cooldown
- 다음 클라우드 후보 ≤3
- 전부 실패 → `supportFallback` (local generation 꺼져 있으면 **렌즈 힌트 없음**)

일반 chat: `LlmRouterBandit` 20s cooldown, `ChatWorkflow.callWithRetry`, Gemini Resilience4j circuit breaker, 로컬 GPU device-lost quarantine.

모델 폐기 전용 핸들러는 없다. 잘못된 모델 ID는 HTTP 404/`MODEL_MISSING`로 다음 fallback.

---

## B. 공식 문서와 불일치하는 부분

확인일: 2026-09-19. 공식 문서를 블로그보다 우선.

### B.1 OpenAI GPT-5.6 호출 방식

| 항목 | 내용 |
|---|---|
| 파일 | `LlmRouterAspect.java` `apiAttempt` / `OpenAiChatModel.builder`; `ConversateApiRouteTest.java` |
| 현재 동작 | Display OpenAI 힌트는 **`POST /v1/chat/completions`**. payload: `reasoning_effort=low`, `max_completion_tokens`, `service_tier=default`, JSON schema/`json_object`. 테스트가 `gpt-5.6-luna`에 대해 이 계약을 고정. |
| 공식 현재 계약 | [Models](https://developers.openai.com/api/docs/models) (확인 2026-09-19): `gpt-5.6-luna` $0.20/$1.20, `gpt-5.6-terra` $2/$12, `gpt-5.6-sol` $4/$20, context 1.05M. “Models are available via the Responses API”. [Migrate to Responses](https://developers.openai.com/api/docs/guides/migrate-to-responses): Chat Completions **still supported**, Responses **recommended**. GPT-6 Astra는 tool calling에 Responses 필요. |
| 문제 가능성 | Chat Completions는 아직 지원되므로 즉시 고장은 아님. Responses로 바꾸면 LangChain4j 1.0.1 기본 경로를 벗어나 `OpenAiResponsesChatModel`로 가야 함. **model-guard 기본이 `SUBSTITUTE_CHAT`** 이라 `responses-only-prefixes`에 `gpt-5.6`을 넣으면 로컬 `gemma4:26b`로 치환되어 Display 힌트가 멈춘다. |

가격 YAML은 공식과 **일치**. 유지.

### B.2 `nova.orch.model-guard.responses-only-prefixes`

| 항목 | 내용 |
|---|---|
| 파일 | `application-llm.yaml` L361–374; `NovaModelGuardProperties.java`; `ModelGuardSupport.isResponsesOnlyModel` (exact 또는 `prefix-`) |
| 현재 동작 | mode=`SUBSTITUTE_CHAT`. YAML prefixes: `gpt-4.1`, `gpt-4o`, `gpt-4.5`, `o1`, `o3`, `o4`, `gpt-5.5-pro`. 매칭 시 substitute=`llm.chat-model`=`gemma4:26b`. |
| 공식 계약 | gpt-4o는 chat 모델이다. Responses-only가 아님. Java 코드 기본 리스트는 `gpt-5-pro`, `gpt-5.1-codex`, `o3-deep-research` 등. |
| 문제 가능성 | Display 힌트(`gpt-5.6-luna`)는 prefix 미매칭이라 **현재는 안전**. 다만 누군가 `gpt-4o`를 켜면 클라우드가 로컬 Gemma로 바뀐다. `gpt-5.6`을 이 리스트에 추가하는 패치는 **금지**. |

### B.3 `application-llm.yaml` 클라우드 기본 모델 ID (disabled 상태)

| 키 | llm.yaml 기본 (disabled) | meta-display override (enabled) | 공식 2026-09-19 |
|---|---|---|---|
| openai-premium | `gpt-5.5` | `gpt-5.6-sol` | GPT-5.5는 카탈로그에 남아 있음. 현 플래그십 패밀리는 5.6 / GPT-6 Astra |
| openai-balanced | `gpt-5.4-mini` | `gpt-5.6-terra` | 공식 모델 페이지 전면에 없음 |
| gemini-pro | `gemini-2.5-pro` | `gemini-3.8-flash` | 2.5 Pro는 아직 목록에 있음. 3.8 Flash가 현 Flash GA |

`Start-RAG`는 meta-display를 켜므로 Display는 새 ID를 쓴다. **profile 없이 llmrouter 클라우드를 켜면 구 ID가 나간다.**

### B.4 Gemini 기본 모델

| 항목 | 내용 |
|---|---|
| 파일 | `GeminiGateway.java` `DEFAULT_MODEL=gemini-2.5-flash`; `application.yml` `gemini.gateway.models.default` |
| 현재 동작 | 힌트 라우터는 `gemini-3.5-flash-lite`. 스피치는 같은 3.5-flash-lite. 그 외 purpose(search-expansion 등)는 2.5-flash. |
| 공식 | [Models](https://ai.google.dev/gemini-api/docs/models) 2026-09-17: `gemini-3.5-flash-lite` stable, `gemini-3.8-flash` new stable. `gemini-2.5-flash` 아직 존재. **`gemini-2.0-flash` shut down**. OpenAI-compat: `https://generativelanguage.googleapis.com/v1beta/openai/` ([docs](https://ai.google.dev/gemini-api/docs/openai) 2026-09-02). 3.8 마이그레이션: temperature/top_p/top_k 제거 권고. Gemini 3는 thinking off 불가. |
| 가격 | 3.5-flash-lite $0.30/$2.50, 3.8-flash intro $0.75/$3.75 through 2026-12-31 — YAML과 일치. |
| 문제 가능성 | Display 힌트 ID는 최신. 비-cue Gemini purpose가 2.5-flash를 쓰면 구세대이지 폐기 모델은 아님. 3.8-flash에 temperature를 넘기면 무시/경고 가능 (`LlmRouterAspect`가 temperature를 GeminiGateway에 전달). |

### B.5 Groq

공식 [Supported Models](https://console.groq.com/docs/models): `openai/gpt-oss-20b` $0.075/$0.30 ~1000 t/s, `openai/gpt-oss-120b` $0.15/$0.60, context 131072. OpenAI-compat `https://api.groq.com/openai/v1`. Whisper `whisper-large-v3-turbo` $0.04/hour. YAML 가격 일치. **불일치 없음.**

참고: Llama 3.1 8B instant는 free/developer에서 내려간 정황(커뮤니티). 이 코드는 gpt-oss를 쓰므로 해당 없음.

### B.6 `configs/api-routing.yaml` vs Display 런타임

인벤토리/스킬은 “LLM은 로컬 우선”. Display 힌트 코드는 로컬을 **고의로 스킵**. 문서가 코드를 이기면 6.5s 힌트 예산 안에 로컬 27B/26B를 넣게 된다. **코드가 이긴다.** 다음 에이전트는 api-routing.yaml을 이유로 cue admission을 로컬화하지 말 것.

### B.7 LangChain4j 1.0.1

`OpenAiChatModel` = Chat Completions. `maxCompletionTokens`를 코드가 호출함 (`LlmRouterAspect` L1208). 주석(`OpenAiTokenParamCompat`)은 “1.0.1은 max_completion_tokens 미지원이라 skip”이라고 써 있어 **주석이 코드와 불일치**. 동작은 max_completion_tokens를 보냄 (테스트 증명).

### B.8 긴 컨텍스트

공식 창: Luna 1.05M, Gemini 1M, Groq 131k. 코드가 힌트에 보내는 것: 최근 12턴/8192자 + evidence 3개. **긴 컨텍스트는 이 제품에 필요 없다.** 창을 키우거나 1M 모델을 고르는 변경은 기각.

---

## C. 유지할 부분

이유: 이미 Display 실시간 제약에 맞춰져 있고, 바꾸면 지연/비용/장애 전파가 커진다.

1. **Display 힌트에서 로컬 Ollama 제외** (`ConversateCueRoutingPolicy.local()`). GPU 로딩은 6.5s 예산을 쉽게 넘긴다. `qwen3.5:9b`를 cue 1차로 올리지 말 것.
2. **Groq 유료 금지** (`daily-budget-usd: 0` + free-tier guard). 싼 속도는 유지하되 과금 폭주 차단.
3. **힌트 캡**: 640 tokens / 612자 / 4–8줄 / 20s TTL / 비스트리밍 JSON. CSS·렌즈와 이미 맞춤.
4. **FAST vs RAG_CUE 로컬 게이트** (`ConversateQuestionPolicy`). 정의 질문에 검색을 강제하지 않음. LLM 게이트(`cueGate`)를 다시 켜지 말 것 — 게이트 자체에 3s+클라우드가 붙는다.
5. **빈 검색 시 일반 힌트 보존** (`demo1-conversate-hint-evidence`). 고정 거절 문구로 덮지 않음.
6. **LangChain4j 1.0.1 순도**. 새 provider SDK를 넣지 말 것. OpenAI-compat HTTP만.
7. **KeyResolver 중복 키 fail-closed**. openssl/키 이름 재구성 금지.
8. **기존 fallback 폭 (≤3 cloud attempts, 시간 분할)**. 더 늘리면 p95만 증가.
9. **GPT-6 Astra / Anthropic / xAI Grok / Kimi를 힌트 경로에 추가하지 않음** (섹션 F).
10. **일반 RAG 로컬 우선** (`gemma4:26b` / `qwen3.5:9b`). Display와 분리된 계약. Ollama model lock 유지.
11. **OpenAI/Gemini/Groq 공식 가격 YAML** (2026-09-16 표기, 2026-09-19 공식과 일치).
12. **`reasoning_effort=low` on cue OpenAI 호출** (`ConversateApiRouteTest`). 기본 reasoning을 올리지 말 것.

---

## D. 수정할 부분

각 항목은 **최소 diff**. 전체 파일 재작성 금지. 이번 세션에서는 적용하지 않음.

### D1. model-guard responses-only 리스트 교정

- **우선순위:** P1 (발포 전 발포 — Display 현재 경로에는 안 맞지만, 한 줄 설정 실수로 클라우드→로컬 치환)
- **대상:** `main/resources/application-llm.yaml` `nova.orch.model-guard.responses-only-prefixes` (대략 L367–374)
- **범위:** prefix 리스트만. mode는 `SUBSTITUTE_CHAT`로 유지하거나, Display OpenAI에 한해 별도 처리 (D2). **리스트에 `gpt-5.6` / `gpt-5.6-luna`를 넣지 않는다.**
- **이유:** `gpt-4o`/`gpt-4.1`은 Responses-only가 아닌데 prefix 매칭되면 `gemma4:26b`로 치환.
- **최소 변경:** YAML 리스트를 Java 기본에 가깝게 축소:
  - 유지 후보: `gpt-5-pro`, `gpt-5.1-codex`, `gpt-5-codex`, `o3-deep-research`, `o4-mini-deep-research`, `gpt-5.5-pro`
  - 제거: `gpt-4o`, `gpt-4.1`, `gpt-4.5`, 단독 `o1`/`o3`/`o4` (과매칭)
- **기존 동작:** Display `gpt-5.6-*`는 지금과 같이 chat/completions.
- **이득:** 잘못된 치환 발포 제거.
- **위험:** 정말 Responses-only인 옛 스냅샷 ID가 chat로 가서 4xx. `fallback-to-responses: true`가 이미 있음.
- **검증:** `ConversateApiRouteTest` (luna가 계속 `/v1/chat/completions`). 신규 단위: `ModelGuardSupport.isResponsesOnlyModel("gpt-4o", prefixes)==false`, `"gpt-5.6-luna"==false`.
- **롤백:** YAML 리스트 원복.

### D2. OpenAI 힌트를 Responses로 올리는 것은 조건부

- **우선순위:** P2 / **조건부 채택**
- **대상:** `LlmRouterAspect` model-guard 분기 또는 cue `apiAttempt`만
- **채택 조건 (하나라도):** 라이브 `gpt-5.6-luna` chat/completions가 `use /v1/responses` / `does not support chat completions`를 반환. 또는 `finishReason`/`reasoning` 토큰이 640 예산을 삼켜 힌트가 잘림.
- **비채택 시:** 유지. 공식은 Chat Completions를 아직 지원하고, 테스트가 chat 계약을 고정.
- **최소 변경 (조건 충족 시):** cue OpenAI 라우트만 `OpenAiResponsesChatModel` 사용. **전역 mode를 ROUTE_RESPONSES로 바꾸지 말 것.** substitute를 gemma로 두지 말 것. `store:false` (짧은 힌트, 상태 불필요).
- **영향:** LangChain4j chat builder를 우회. structured output shape가 `text.format`로 바뀜 (`OpenAiResponsesChatModel`가 이미 있음).
- **위험:** LC4j 1.0.1 Responses 미지원 → 커스텀 어댑터 버그, 지연 증가.
- **검증:** 기존 `ConversateApiRouteTest`를 새 endpoint에 맞게 수정 + 합성 HTTP. 유료 라이브는 사용자 승인 후 1회.
- **롤백:** `apiAttempt`를 현 chat/completions으로 복귀.

### D3. profile 없는 클라우드 기본 ID를 meta-display와 정렬

- **우선순위:** P2
- **대상:** `application-llm.yaml` `llmrouter.models.openai-premium.name`, `openai-balanced.name`, `gemini-pro.name` (L520–551). **enabled 플래그는 false 유지.**
- **최소 변경:** 기본 name만 `gpt-5.6-sol` / `gpt-5.6-terra` / `gemini-3.8-flash`로. enabled/weight/fallback-only 불변.
- **이유:** meta-display 없이 누군가 enabled만 켜면 `gpt-5.4-mini` 같은 전면 비공식 ID가 나감.
- **위험:** 구 ID에 의존하는 비-Display 실험. 이 키들은 기본 disabled.
- **검증:** 설정 바인딩 테스트 또는 `ConversateCueRoutingPolicyTest` (meta-display 값은 그대로).
- **롤백:** name 원복.

### D4. Gemini 3.8 temperature sanitize (gemini-pro 라우트만)

- **우선순위:** P2
- **대상:** `LlmRouterAspect` Gemini 분기 (`isGeminiRoute` ~ L1070) 또는 `ModelCapabilities.sanitizeTemperature`
- **최소 변경:** model name이 `gemini-3.8-`이면 temperature/topP를 null로 (공식 3.8 마이그레이션). `gemini-3.5-flash-lite` 1차는 건드리지 않음.
- **이득:** 3.8 품질 라우트 경고/거절 예방.
- **위험:** 3.5-lite에까지 적용하면 샘플링 제어 상실 — 그래서 3.8만.
- **검증:** 단위 테스트 sanitize. 힌트 1차 경로 회귀 없음.
- **롤백:** sanitize 조건 삭제.

### D5. 주석 drift (`OpenAiTokenParamCompat`)

- **우선순위:** P3 (동작 변경 없음)
- **대상:** `main/java/com/example/lms/llm/OpenAiTokenParamCompat.java` 클래스 javadoc
- **최소 변경:** “1.0.1은 max_completion_tokens 미지원이라 skip” 문장을 실제 동작(`LlmRouterAspect`가 `maxCompletionTokens` 설정)에 맞게 수정.
- **검증:** 컴파일만.
- **롤백:** 주석 원복.

### D6. 문서 SSOT에 Display 예외 한 줄

- **우선순위:** P1 (다음 에이전트가 로컬 우선으로 “수정”하는 것을 막음)
- **대상:** `docs/API_ROUTING_SPEC.md` LLM 섹션 + `.agents/skills/demo1-api-routing-inventory/SKILL.md` Do-not
- **최소 변경:** “Meta Display conversate cue는 `application-meta-display.yml` + `ConversateCueRoutingPolicy`가 SSOT이며 로컬 ollama를 admission에서 제외한다. api-routing.yaml의 free_local 우선은 일반 RAG/chat에만 적용.”
- **소스 동작 변경 없음.**

적용하지 말 것 (부정 검증):

- Provider 추가, fallback 단 수 증가, max-output-tokens 상향, context 창 확대, 힌트 스트리밍, PromptBuilder로 cueHint 교체, `cueGate` LLM 재활성화, Groq 유료 예산 개방.

---

## E. 제거/비활성 후보

함부로 삭제하지 말 것. 참조가 남아 있으면 설정으로만 꺼 둔다.

| 후보 | 실제 미사용? | 조치 |
|---|---|---|
| `ConversateCardPrompt.cueGate` | 호출 0. 게이트는 `ConversateQuestionPolicy` | **유지(dead code) 또는 별도 청소 과제**. 힌트 패치와 묶지 말 것 |
| `cloud-models.manifest.yaml` | `catalogTrust: attachment_unverified`, enabled false, 구 ID | **삭제 금지**. 주석으로 SoT 아님만 |
| `configs/models.manifest.yaml` | 스킬이 이미 DEPRECATED-AS-SOT | **삭제 금지** |
| Anthropic 라우트 설정 | fail-closed | **키/어댑터 추가하지 말고 삭제하지도 말 것** |
| `llmrouter.external` OpenCode | enabled false | 유지 |
| GLM subagent | 기본 off | 힌트 경로에 연결하지 말 것 |
| nested `demo-1/` LLM 클래스 | Gradle 제외 | 삭제 금지 (별도 zombie 스킬) |
| `qwen3.5:9b` 설정 | Display 미사용, RAG fast **사용** | **제거 금지** |

---

## F. 신규 API/모델 후보

기본 판정: **추가하지 않는다.** 새롭다는 이유만으로 기각.

| 후보 | 공식 (2026-09-19) | 판정 | 이유 |
|---|---|---|---|
| GPT-6 Astra | $10/$50, 1.05M, Responses, 플래그십 | **기각** | 힌트에 과함. 지연·비용. 공식도 “cost-sensitive는 Luna” |
| xAI `grok-4.6` | [docs.x.ai](https://docs.x.ai/developers/models) $2/$6, 500k, Responses `https://api.x.ai/v1`. `XAI_API_KEY` unset | **기각** | 어댑터 없음, 키 없음, Luna/Flash-Lite보다 비싸고 새 fallback hop이 6.5s를 잠식 |
| Anthropic Claude | native Messages API. 키 unset. 코드가 native 거부 | **기각** | OpenAI-compat 우회 스택 + 키 + 지연 |
| Kimi / Moonshot | 키 unset, 클라이언트 없음 | **기각** | |
| GLM-4/5 on 힌트 | ZAI 키는 있으나 힌트 라우트 없음 | **기각** | 서브에이전트와 힌트를 섞지 말 것 |
| `gemini-3.1-flash-lite` ($0.25/$1.50) | 3.5-lite보다 저렴 | **조건부 보류** | 한국어 짧은 힌트 품질 증거 없음. 라이브 A/B 없이 교체 금지 |
| Groq `qwen/qwen3.8-27b` preview | preview, 비생산 | **기각** | preview 단종 위험, 힌트에 27B 불필요 |
| 힌트 1차로 로컬 `qwen3.5:9b` | 설치됨, 무료 | **기각 (현재)** | 정책이 고의 제외. GPU 콜드스타트가 실시간 예산을 깨는 반례가 이미 있음. 로컬은 클라우드 전멸 후 generation opt-in만 |

---

## G. 실제 실행용 수정 지시서 (다음 에이전트)

전제: Project Root `C:\AbandonWare\demo-1\demo-1\src`. 소스 쓰기 전 `$demo1-source-edit-three-way-preflight` / work-ledger. LangChain4j 1.0.1 유지. 비밀값 로그 금지. Display Java 변경 후 `Start-RAG.bat` (DevWatch/ForceRestart). **한 번에 한 seam.**

### Step 0 — 하지 말 것

- `ConversateCueRoutingPolicy.local()` 삭제/완화
- Groq `daily-budget-usd` > 0
- Anthropic/xAI/Kimi/GLM/GPT-6를 `llmrouter.models`에 추가
- `gpt-5.6*`를 `responses-only-prefixes`에 추가
- `max-output-tokens` / 612자 캡 변경
- `PromptBuilder`로 `cueHint` 교체
- `dev.langchain4j` 버전/모듈 추가
- 유료 라이브 팬아웃 (`AWX_AGENT_ALLOW_PAID_MODELS` 없이)

### Step 1 — 문서 가드 (D6, 소스 동작 없음)

`docs/API_ROUTING_SPEC.md` LLM 표 아래에 Display 예외 3–5줄. 스킬 Do-not에 동일 한 줄.

### Step 2 — model-guard prefix (D1)

`application-llm.yaml`만. 테스트:

```
.\gradlew.bat test --tests ai.abandonware.nova.orch.aop.ConversateApiRouteTest --tests com.example.lms.assist.ConversateCueRoutingPolicyTest
```

`gpt-5.6-luna`가 계속 chat/completions인지 확인.

### Step 3 — (선택) disabled 클라우드 name 정렬 (D3)

enabled 플래그 변경 금지.

### Step 4 — (선택) Gemini 3.8 temperature (D4)

`gemini-3.5-flash-lite` 경로에 영향 없으면.

### Step 5 — Responses 이관은 라이브 증거가 있을 때만 (D2)

증거 없으면 중지.

### 적용 순서 한 줄

문서 가드 → model-guard YAML → (선택) llm.yaml names → 테스트 → Display를 건드렸으면 Start-RAG. 힌트 라우팅 Java는 이번 지시서의 기본 범위가 **아님**.

---

## H. 검증 명령

현재 프로젝트에 실제로 있는 경로만. 이 지시서 작성 세션에서는 실행하지 않음 (유료 금지, 사용자 요청이 compile이 아님).

### H.1 compile / purity

```
cd C:\AbandonWare\demo-1\demo-1\src
.\gradlew.bat :compileJava -x test
.\gradlew.bat checkLangchain4jVersionPurity
powershell -NoProfile -File scripts\check-model-lock.ps1
```

### H.2 관련 테스트 (최소)

```
.\gradlew.bat test --tests com.example.lms.assist.ConversateApiCueServiceTest --tests com.example.lms.assist.ConversateCueRoutingPolicyTest --tests com.example.lms.assist.ConversateLocalCueAdmissionTest --tests com.example.lms.assist.ConversateGatewayFailoverTest --tests ai.abandonware.nova.orch.aop.ConversateApiRouteTest
```

있으면 추가: `Fold6BackgroundContextTest`.

`ConversateApiRouteTest` 고정 계약:

- `apiAttempt("local")` 와이어 0
- OpenAI cue = `/v1/chat/completions` + `reasoning_effort=low` + max_completion_tokens
- `gpt-5.6-luna`도 동일
- subsecond timeout이 1초로 반올림되지 않음

### H.3 smoke (유료 없이)

- `ollama ls` + `http://127.0.0.1:11434/api/tags` + `:11435/api/tags`
- env 이름 존재만 (`docs/API_ROUTING_SPEC.md` 인벤토리 명령). 값 출력 금지
- 합성 fixture HTTP (위 테스트의 로컬 HttpServer). **OpenAI/Gemini/Groq 실호출 금지** unless user explicitly asks

### H.4 provider 오류 / fallback

단위: `ConversateGatewayFailoverTest`, `ConversateApiCueServiceTest` empty-search / invalid JSON / timeout.

라이브 18180은 이 조사 시점 **not_observed**. 라이브 확인이 필요하면 사용자가 Start-RAG를 승인한 뒤에만:

- Fold6 진단의 `selectedModel` / `hintAttempts` / `routesSkipped` (비밀 없이)
- 한 provider 키를 잠깐 빼는 실험은 **하지 말 것** (설정 변경 금지 범위). 대신 진단의 skip reason을 읽는다.

---

## 교차검증 요약 (긍정 / 부정 / 중립)

**긍정.** Display는 이미 짧은 JSON 힌트, 비용 가드, EWMA, 3단 클라우드, Groq 무료 전용, 로컬 제외, FAST 게이트를 갖고 있다. OpenAI/Gemini/Groq 모델 ID·가격은 2026-09-19 공식과 맞다. LangChain4j 1.0.1 + OpenAI-compat는 유지할 만하다.

**부정.** Provider를 늘리면 6.5s 안에 직렬 재시도가 늘어 p95만 커진다. `responses-only`에 5.6을 넣으면 SUBSTITUTE_CHAT가 힌트를 Gemma로 보낸다. 로컬 1차화는 GPU 콜드스타트가 렌즈를 비운다. GPT-6 Astra/Grok/Claude는 비용·어댑터·키 부재.

**중립 판정.**

| 제안 | 판정 |
|---|---|
| Display 클라우드 1차 + 로컬 제외 | **유지** |
| Groq free-only gpt-oss-20b | **유지** |
| gpt-5.6-luna / gemini-3.5-flash-lite / 공식 가격 | **유지** |
| Chat Completions + reasoning_effort=low | **유지** (Responses는 라이브 실패 증거 시에만) |
| model-guard gpt-4o prefix | **수정** (P1) |
| llm.yaml disabled 구 모델 ID | **조건부 수정** (P2, enabled는 그대로 false) |
| API_ROUTING_SPEC Display 예외 | **수정** (문서, P1) |
| 신규 provider | **제거 후보가 아니라 추가 기각** |
| 힌트에 qwen3.5:9b | **유지(제외)** |

---

## 한 줄 실행 요약

가장 먼저 고칠 것 (최대 3):

1. `API_ROUTING_SPEC` / 라우팅 스킬에 **Display cue는 로컬 제외**라고 명시해, 다음 패치가 로컬 1차로 되돌리지 못하게 한다.
2. `nova.orch.model-guard.responses-only-prefixes`에서 `gpt-4o`/`gpt-4.1` 등 chat 모델을 제거한다. **`gpt-5.6`은 넣지 않는다.**
3. (여유 시) disabled `llmrouter` 클라우드 name을 meta-display와 맞춘다.

건드리지 말아야 할 것 (최대 3):

1. `ConversateCueRoutingPolicy`의 로컬 스킵, Groq 유료 0, 640/612 캡.
2. LangChain4j 1.0.1 및 새 vendor SDK.
3. Anthropic / xAI / Kimi / GLM / GPT-6 Astra를 힌트 fallback에 추가.

추가 사실확인이 필요한 것:

- 이 계정의 `gpt-5.6-luna`가 chat/completions에서 실제로 200인지 (유료 1회, 사용자 승인 필요). 코드+공식+단위테스트는 “지원됨”이지 “이 키로 성공”이 아님.
- meta-display 런타임에서 `gemini-cue` vs `openai-economy` vs `groq-gate` 중 무엇이 1등으로 붙는지 (18180 not_observed, 키 존재만 확인).
- `conversate.generation.enabled`가 다른 overlay에서 켜지는지.

확신: **중간** — 소스·설정·공식 문서·단위테스트 계약은 높음. 라이브 18180 힌트 1등 provider와 OpenAI 계정별 chat vs responses 동작은 이 세션에서 유료 호출을 하지 않아 중간.
