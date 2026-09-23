# LLM API 소스수정 지시서 — 독립 검증 보고서 (GPT PRO 전달용)

- 검증일: 2026-09-19
- 대상 문서: `docs/llm-api-source-audit-directive-20260919.md`
- 검증자: Devin (지시서 작성 세션과 별개의 독립 재검증)
- 방법: 활성 소스 정적 확인(`main/java`, `main/resources`, `app/src/main/java_clean`) + 로컬 프로브(`ollama ls`, `:11434/:11435 /api/version`, env 이름 존재만) + 공식 문서 웹 대조(OpenAI/Gemini/Groq). **유료 API 호출 0건, 소스 변경 0건.**
- 비밀값: 미출력 (환경변수 이름·존재 여부만)

## 종합 판정

**채택 가능 — 정확도 높음.** 코드·설정 주장 전부 재확인. 경미한 표기 드리프트 4건(§4), 작성 시점 이후 변화 1건(§5), 미검증 잔여 5건(§6, 지시서도 동일하게 인정). 지시서의 "수정할 부분(D1–D6)" 판정은 §7 표와 같다.

---

## 1. §A 현재 실제 구조 — 전부 확인

| 지시서 주장 | 검증 결과 | 증거 |
|---|---|---|
| 소스셋: `main/java`+`main/resources` canonical, `:app`=`app/src/main/java_clean`+`app/src/main/resources` | **확인** | `app/build.gradle.kts` L82–85 `java.setSrcDirs(["src/main/java_clean"])`; `build.gradle.kts` `checkSourceSetHygiene`(L163–202)이 동일 4루트 강제; `settings.gradle`이 활성 설정(`.kts`는 HOOK SENTINEL 명시), `:demo-1`은 `includeLegacyModules` 기본 false → 제외 |
| LC4j 순도 `1.0.1` 2개 모듈만 | **확인** | `build.gradle.kts` L134–135 (`langchain4j`, `langchain4j-open-ai`만), `checkLangchain4jVersionPurity` L150–160 |
| profile `local` 기본, Start-RAG=`local,meta-display` | **확인** | `application.yml` L5 (`default: local`), L7 (`optional:classpath:application-llm.yaml` import); `start_rag_stack.ps1` L328 `-SpringProfile local,meta-display` |
| `ollama ls` = allowlist 일치 | **확인 (라이브)** | `ollama ls` 13개 = `configs/api-routing.yaml` installed_models.all과 정확히 일치 |
| 11434/11435 `/api/version` 응답 | **확인 (라이브)** | 둘 다 `0.32.13` 응답 |
| env 이름 set/unset 목록 | **확인 (라이브)** | set: OPENAI/GROQ/GEMINI/BRAVE/DEEPGRAM/SONIOX/ZAI/OPENCODE_API_KEY. unset: ANTHROPIC/XAI/MOONSHOT/OPENROUTER_API_KEY, LLM_BASE_URL, LLM_CHAT_MODEL, OLLAMA_HOST — 문서와 **정확히** 일치 |
| 경로1 Display 힌트: 클라우드 우선, 로컬 제외 | **확인** | `application-meta-display.yml` L57–111 라우트 7종(ID·가격·quality·gate 전부 일치), `ConversateCueRoutingPolicy.java` L55+218 `local()`이 `local\|ollama\|local-openai` 제외 |
| 선택 지표 = 가격×EWMA latency/successRate, OpenAI/Gemini primary, cooldown 20/60/300s | **확인** | `ConversateCueRoutingPolicy` L120–122 정렬식, L217 `primary()`={openai,gemini}, L18 `COOLDOWN_MS={20000,60000,300000}`, L144–145 EWMA |
| Groq 유료 금지 (`daily-budget-usd:0`+FreeTierGuard) | **확인** | `application-meta-display.yml` L51–52, `ConversateCueRoutingPolicy` L66 `groq_free_tier_unverified`, `GroqFreeTierGuard` 존재 |
| 경로2 일반 RAG: `llm.provider=local`, chat=gemma4:26b, fast=qwen3.5:9b, judge/coder=smtek/Qwen3.8-27B:Q3_K_XL | **확인** | `application-llm.yaml` L11/L17/L52/L73/L79; `application.yml` L240/L242/L249 (일치, base-url 기본값은 파일 간 11434↔11435 차이 있으나 지시서 서술과 양립) |
| `llmrouter.auto` UCB1 = weight>0만, 클라우드 fallback-only+weight 0 | **확인** | `LlmRouterBandit` L30–35/L186/L463–464; `application-llm.yaml` light=0.45(L501)/gemma=0.55(L510)/클라우드 전부 weight 0.0+fallback-only |
| `llm.gateway.cloud.route-key=api3` | **확인** | `application-llm.yaml` L42 |
| 호출 경로 `DynamicChatModelFactory.lcWithTimeout ← @Around` | **확인** | `LlmRouterAspect` L134 pointcut; `ChatService`→`ChatWorkflow` 위임 `ChatService.java` L30–35 |
| `OllamaNativeChatModel` think=false native, gemma4:26b는 /v1 | **확인** | `OllamaNativeChatModel` L80–83 대상(qwen3:*, qwen3.5:9b, qwen3-vl, gemma4:12b), L260 `think=false`, L1053 `/api/chat`. gemma4:26b 미포함 → /v1 경로 |
| §A.3 호출 체인 전체 | **확인** | `DisplayConversateController` audioStart/chunk/stop(L389/408/415), `ConversateAsrBridge` L33 provider 기본 `local` + L49/L294 로컬 Python child + 미완성 시 cloud 후보(L239–248); `ConversateCloudStt.transcribeEconomy` L206 → Groq `whisper-large-v3-turbo`(L213/241) → Gemini `SPEECH_MODEL=gemini-3.5-flash-lite`(GeminiGateway L50, generateContent L72); `ConversateSessionService` L76–77 `@Autowired(required=false) apiCues`→pipeline 주입, L237 `answerPublicDisplay`; `ConversateAnswerPipeline` L58 `if(cues!=null) return cues.answer(...)` → ChatService 우회 확정 |
| gate=로컬 규칙, RAG_CUE만 검색(web only), evidence 최대 3, cueHint 사용/cueGate 미사용, apiAttempt+비스트리밍 | **확인** | `ConversateQuestionPolicy.cueDecision` L39/L60/L77 (NO_CUE/CUE/RAG_CUE); `ConversateApiCueService` L101 RAG_CUE 분기, L224–226 `useWeb=true`+vector/kg/self-ask 등 전부 false, L147–148/L597 cap 3, L409 `apiAttempt(...).chat(messages)` 비스트리밍; `ConversateCardPrompt.cueGate`(L18) 호출자 0건 = 미사용, `cueHint`(L37) 사용 |
| 타임아웃 total 12s/gate 3s/hint 6.5s/시도≤3/토큰 640/612자 | **확인** | `application-meta-display.yml` L32–36; `ConversateApiCueService` L376 `max-attempts-per-stage`≤3, L379 `max-output-tokens`(YAML 640, 코드 기본 1024), L479 gate?3000:6500 기본; `parseHint` L565 `codePointCount>612` 하드캡 |
| 스트리밍 없음, SSE는 인증 conversate 페이지 | **확인** | cue 경로는 `ChatModel.chat()`만; SSE 엔드포인트는 `ConversateController` L94 `/api/assist/sessions/{id}/output`(인증 경로) — 렌즈는 POST poll 계열 |
| §A.4 클래스 표 전체 | **확인** | 모든 파일 존재·역할 일치 (`OpenAiResponsesChatModel`은 `LlmRouterAspect` L1048 ROUTE_RESPONSES 분기에서만 사용 → "조건부" 정확) |
| §A.5 미사용/fail-closed 표 | **확인** | Anthropic `failUnsupportedProvider("unsupported_anthropic_native_route")` L1478 + env unset; xAI/Kimi HTTP 클라이언트 0건 + env unset (`GrokPromotionDiscovery`=검색 정책, `grok-build-`/`kimi-`=`ExternalFreeModelPolicy` L63–64 autolearn prefix — 문서 서술 정확); `llmrouter.external` enabled:false(L573); GLM `zai/glm-5.3-flash`+`agent.subagent.glm.enabled` 기본 false+`GLM_EXTERNAL_READY`+`AI_GATEWAY_API_KEY`(`SubagentProviderConfiguration` L30–33/L85–86); mistral-medium enabled:false(L553); `ChatLanguageModel` 0건; `llmrouter.models.light`는 local이라 cue 제외 |
| §A.6 오류 분류 | **확인** | `quotaExhausted` L498–507 `insufficient_quota\|credit_balance_exhausted\|spend_limit_exceeded`(429 본문 error.code + LlmGatewayException reasonCode); `ConversateLocalCardGenerator.classify` L88 `RATE_LIMIT_COOLDOWN→GENERATION_RATE_LIMITED`; 계정 5분 block `ConversateCueRoutingPolicy` L152–153; `supportFallback` L509–525(`conversate.cue.local-support-enabled` + `conversate.generation.enabled:false` 기본 → 둘 다 꺼지면 힌트 없음 정확) |

## 2. §B 공식 문서 불일치 — 확인

| 항목 | 검증 결과 | 증거 |
|---|---|---|
| B.1 Display OpenAI 힌트 = `/v1/chat/completions` + `reasoning_effort=low` + `max_completion_tokens` + `service_tier=default` + JSON schema | **확인** | `LlmRouterAspect` L1205–1209 tokenParamKey→maxCompletionTokens, L1220 `reasoningEffort("low")`(gpt-oss/gpt-5.6*), L1221 `serviceTier("default")`, L1213–1219 JSON schema/json_object; `ConversateApiRouteTest` L23/L31–41이 동일 계약 고정(local 와이어 0, luna 동일, subsecond 미반올림) |
| B.1 model-guard `SUBSTITUTE_CHAT` 기본 + `gpt-5.6` prefix 넣으면 힌트가 gemma로 치환 | **확인** | `application-llm.yaml` L363 mode=SUBSTITUTE_CHAT, L365 substitute=`${llm.chat-model}`(=gemma4:26b); `ModelGuardSupport.isResponsesOnlyModel` L42 `equals(p)\|startsWith(p+"-")` → `gpt-5.6` prefix는 `gpt-5.6-luna` 매칭. **지시서 경고 정확** |
| B.2 YAML prefix 리스트(L367–374: gpt-4.1/4o/4.5/o1/o3/o4/gpt-5.5-pro) + Java 기본 리스트 | **확인** | `application-llm.yaml` L367–374 정확; `NovaModelGuardProperties` L51–57 기본=[gpt-5-pro, gpt-5.1-codex, gpt-5-codex, o3-deep-research, o4-mini-deep-research]. **주의: Spring List 바인딩은 대체이므로 현 유효 리스트 = YAML 7종뿐** (Java 기본 5종은 현재 비활성) — D1 유지 후보 선정 시 반드시 5종 복원 필요(지시서는 이미 포함) |
| B.3 disabled 기본 ID: gpt-5.5/gpt-5.4-mini/gemini-2.5-pro (L520–551) | **확인** | `application-llm.yaml` L526/L536/L547, enabled 기본 false(L521/L532/L542) |
| B.4 `GeminiGateway.DEFAULT_MODEL=gemini-2.5-flash`, SPEECH=3.5-flash-lite, 타 purpose 2.5-flash | **확인** | `GeminiGateway` L40/L50; `application.yml` L433–440(기본·search-expansion 등 2.5-flash, curation/router 2.5-pro); `LlmRouterAspect` L1065/1089 Gemini에 temperature 전달 |
| B.5 Groq 불일치 없음 | **확인** | 공식 Supported Models: gpt-oss-20b $0.075/$0.30 ~1000t/s, gpt-oss-120b $0.15/$0.60, ctx 131072, `api.groq.com/openai/v1` — YAML과 일치. Llama 3.1 8B가 Enterprise 전용 이동(2026-08-26)도 부합 |
| B.6 api-routing.yaml 로컬 우선 vs Display 로컬 제외 | **확인** | `configs/api-routing.yaml` policy.order=[free_local,…], llm 섹션 ollama_* free_local 선두; cue는 `local()` 제외 → "코드가 이긴다" 정확 |
| B.7 주석 drift (`OpenAiTokenParamCompat`) | **확인** | javadoc L9–11·L87 "1.0.1은 max_completion_tokens 미지원→skip" vs 실제 `LlmRouterAspect` L1209 `b.maxCompletionTokens(...)` 호출 — 주석이 코드와 불일치(지시서 정확) |
| B.8 긴 컨텍스트 불필요 (12턴/8192자+evidence 3) | **확인** | `ConversateSessionService` L400–401(usesApiCues 시 12턴/8192자), `ConversateApiCueService` L529 `boundedContext(12,8192)`, evidence cap 3 |
| 공식 가격(OpenAI/Gemini/Groq) 일치 | **확인 (웹 대조)** | gpt-5.6-luna $0.20/$1.20·terra $2/$12·sol $4/$20·astra $10/$50 공식 표와 일치; gemini-3.5-flash-lite $0.30/$2.50 stable, gemini-3.8-flash GA + intro $0.75/$3.75(2026-12-31까지)→$1.50/$7.50(2027) — YAML 일치 + `ConversateCueRoutingPolicy` L188–189에 2027 인상분 이미 반영; `reasoning_effort=low` 유효 값(none/low/medium/…); OpenAI cache-write 1.25× = 코드 `writeFactor 1.25`(L192)와도 일치 |

## 3. §C 유지 항목 / §E 제거 후보 / §F 신규 기각 / §G 실행 순서

- §C 12개 항목 전부 코드 근거 확인(상기 §1–2 증거와 동일).
- §E: `cueGate` 호출 0건·manifest 두 파일 SoT 아님·Anthropic fail-closed·external disabled·GLM 기본 off·nested demo-1 Gradle 제외·`qwen3.5:9b`는 RAG fast 레인 **사용 중** — "제거 금지" 판정 정확.
- §F: xAI/Kimi/Anthropic 클라이언트·키 부재, GLM은 서브에이전트 전용 게이트 — 기각 판정 정당. `gemini-3.1-flash-lite`는 공식 목록에 존재 확인("조건부 보류" 적절).
- §G Step 0 금지 목록 전부 현재 코드 불변식과 일치.

## 4. 발견된 드리프트 (경미 — 판정 영향 없음)

1. `models.manifest.yaml`을 "구 OpenRouter"로 기술 — 실제 파일은 로컬 모델 매니페스트(`version: 2026-05-28`, openrouter 문자열 0건; `app/…` 사본도 동일). "SoT 아님" 판정은 유지.
2. `legacy.langchain4j-beans.enabled=false` — 명시 `=false` 설정 라인은 없음. 실제 게이트는 `LangChain4jBeans`의 `@ConditionalOnProperty(havingValue="true")`(L30) → **부재 시 자동 비활성**. 실효 동일.
3. B.7 라인 오차 1줄: `b.maxCompletionTokens` 실제 위치 `LlmRouterAspect` L1209 (문서는 L1208 표기; `tokenParamKey` 호출은 L1205).
4. §A.3 게이트 결과 표기 `CUE(FAST)` — 코드 결정값은 `CUE`; "FAST"는 `cueHint` 비-RAG 프롬프트 라벨(`ConversateCardPrompt` L48). 의미 동일.

## 5. 작성 시점 이후 라이브 변화

- `127.0.0.1:18180` 현재 **응답 중**(미인증 POST → 403). 문서의 `not_observed`는 작성 시점에 정확했으며, 현재는 wear 런타임이 기동 상태(PROJECT_STATUS §3와 일치).
- `CONVERSATE_ASR_PROVIDER`=set(len=6) → "local"과 일치; `AI_GATEWAY_API_KEY`=set이지만 GLM은 enabled=false+EXTERNAL_READY 게이트로 여전히 비활성(키 존재 ≠ 활성 — 문서 판단과 일치).

## 6. 미검증 잔여 (지시서도 동일하게 인정 — 유료/라이브 필요)

1. `gpt-5.6-luna`가 **이 계정 키로** chat/completions 200을 내는지 — 유료 1회, 사용자 승인 필요.
2. meta-display 런타임의 1등 라우트(gemini-cue vs openai-economy vs groq-gate) — 라이브 진단 필요.
3. `conversate.generation.enabled`를 켜는 다른 overlay 존재 여부 — 활성 소스에서는 기본 false만 확인.
4. 공식 "Migrate to Responses" 가이드의 정확한 문구 — 부분 확인(공식 모델 페이지는 Responses 추천 톤, Chat Completions 지속 지원과 모순 없음).
5. `gemini-2.0-flash` shutdown 시점, xAI `grok-4.6` $2/$6, Groq Whisper $0.04/hour — 이번 무료 검색에서 직접 확인 못함(방향 일치, 판정 영향 없음).

## 7. D 항목별 판정 (GPT PRO 용)

| 항목 | 판정 | 근거 요약 |
|---|---|---|
| D1 model-guard prefix 교정 (P1) | **GO** | §2 확인. 단, 유지 후보에 Java 기본 5종(gpt-5-pro, gpt-5.1-codex, gpt-5-codex, o3-deep-research, o4-mini-deep-research) 반드시 포함 — YAML 리스트는 Java 기본을 **대체**하므로 생략 시 해당 모델 가드 상실 |
| D2 Responses 이관 (P2 조건부) | **HOLD** | 채택 조건(라이브 실패 증거) 미충족. `OpenAiResponsesChatModel` 존재 확인. 전역 ROUTE_RESPONSES 금지 조건 유효 |
| D3 disabled 클라우드 name 정렬 (P2) | **GO** | 대상 L526/L536/L547, enabled=false 기본 확인 — name만 바꾸는 최소 diff로 충분 |
| D4 Gemini 3.8 temperature sanitize (P2) | **GO** | `ModelCapabilities.sanitizeTemperature`(L1065 호출) + Gemini 분기(L1070) 존재. `gemini-3.8-` 한정 조건 필수(3.5-lite 보호) |
| D5 주석 drift (P3) | **GO** | L9–11·L87 drift 실재, 주석만 수정 |
| D6 문서 SSOT Display 예외 (P1) | **GO** | `API_ROUTING_SPEC.md` §4(L127–141)에 Display cue 예외 없음 확인; `demo1-api-routing-inventory` SKILL.md Do-not 섹션 존재 |

## 8. §H 검증 명령 유효성

- `gradlew.bat :compileJava -x test`, `checkLangchain4jVersionPurity`, `scripts/check-model-lock.ps1` — 파일·태스크 전부 존재 확인.
- §H.2 테스트 FQCN 6종 전부 존재: `ConversateApiCueServiceTest`, `ConversateCueRoutingPolicyTest`, `ConversateLocalCueAdmissionTest`, `ConversateGatewayFailoverTest`, `ai.abandonware.nova.orch.aop.ConversateApiRouteTest`, `Fold6BackgroundContextTest` (`src/test/java`).
- `ConversateApiRouteTest` 고정 계약 기술 정확(L23/31–41로 재확인).

## 9. 한 줄 결론

지시서는 **코드·설정·공식 문서·테스트 계약 모두에서 재현 확인**되었고, 판정(유지/수정/기각)은 건전하다. 적용 시 주의점은 단 하나: **D1에서 Java 기본 responses-only 5종을 유지 후보에 반드시 포함**할 것(YAML 리스트는 대체 동작). 나머지는 지시서 그대로 진행 가능. 미검증 잔여는 전부 "유료 호출/라이브 관측" 영역으로, 문서가 스스로 한계를 정확히 선언한 부분과 일치한다.
