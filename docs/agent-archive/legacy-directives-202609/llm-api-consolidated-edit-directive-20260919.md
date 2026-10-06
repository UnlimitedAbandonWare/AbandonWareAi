# LLM API 감사 취합 보고서 + 소스수정 지시서 (통합본)

- 작성일: 2026-09-19 · 작성자: Devin · taskId: `llm-audit-consolidated-report-f57ea167`
- 루트: `C:\AbandonWare\demo-1\demo-1\src` · 활성 소스셋: `main/java` + `main/resources`
- 입력: {스터프1}=Kimi Tavily/SerpAPI 라이브 키 테스트, {스터프2}=GPT Pro Tavily/SerpAPI 정적 조사, {스터프3}=LLM API 감사 지시서 검증 보고서, {스터프4}=정정·추가 검증 지시, {스터프5}=/chat 재편 분석, {스터프6}=검증 세션 로그
- 방법: 활성 소스 재대조(파일:라인 인용) + 기존 검증 결과 재사용. **유료 호출 0, 소스 변경 0 — 리포트 전용.**
- 상태 표기: `[확인]` 소스/실행 근거 있음 · `[정정]` 입력 문서의 오류 · `[미검증]` 증거 부족

---

## 1. 한 줄 결론

입력 6건을 실제 소스 기준으로 취합하면 **적용 가치가 있는 소스 수정은 3건**(quota 분류 확장, Gemini 3.8 샘플링 필드 제거, 주석 드리프트 교정), **선택적 정리 1건**(죽은 placeholder 삭제), **문서 정정 3건**, 나머지는 **보류/기각**이다. 구조를 깨는 변경(Responses 일괄 이관, Tavily 전체 제거/활성화, searchMulti 우선순위 변경)은 근거가 없거나 현 구조와 충돌하므로 지시하지 않는다.

## 2. 입력 문서별 판정

| 입력 | 내용 | 판정 |
|---|---|---|
| {스터프1} | Tavily/SerpAPI 키 라이브 테스트 — 둘 다 HTTP 성공 | **채택(부분 정정)** — "키+엔드포인트 유효"는 입증. 단 "SerpAPI 완전 작동, 지우면 안 됨"은 과도: 키 유효 ≠ 서빙 경로 사용(§3.4). `scripts/test_tavily_serpapi_keys.ps1` 실재 [확인] |
| {스터프2} | Tavily/SerpAPI 정적 조사 | **채택(3건 정정)** — placeholder 판정 정확. 단 `resolveProviders` 폴백 설명과 searchMulti SerpAPI 가능성이 소스와 다름(§3.4) |
| {스터프3} | 지시서 독립 검증 — 코드·설정 주장 전부 재확인 | **채택** — 본 세션 재대조에서도 §A/§B 주장 재현 확인. 단 "len=6=local"·"18180 403=wear 정상"·"미검증 잔여=전부 유료 영역" 3건은 {스터프4} 지적대로 정정 |
| {스터프4} | 정정·추가 검증 지시 | **대부분 채택** — quota 400/`blocked_api_access` 공백, Gemini 3.8 필드 제거, resolveProviders 조건 구분이 소스와 일치. 단 "12초 제한 미보장 가능성"은 소스상 이미 보장(§3.6) |
| {스터프5} | /chat 실태 — meta-display가 `demo.interview.enabled:false` | **채택(1건 정정)** — 플래그 false 확인. 단 "application-meta-display.yml이 덮어쓴다"가 아니라 **어느 설정 파일도 true로 설정하지 않음**이 정확(§3.7) |
| {스터프6} | 검증 세션 로그 | **참조** — {스터프3}의 실행 기록. 별도 독립 검증으로 중복 계산하지 않음 |

## 3. 소스 기준 확정 팩트와 정정

### 3.1 모델 가드 (D1 관련)

- [확인] `application-llm.yaml` L361-374: `mode=SUBSTITUTE_CHAT`, `substitute-chat-model=${llm.chat-model}`(=gemma4:26b), `responses-only-prefixes` = **[gpt-4.1, gpt-4o, gpt-4.5, o1, o3, o4, gpt-5.5-pro]** 7종.
- [확인] Java 기본 리스트 `NovaModelGuardProperties` L51-57 = [gpt-5-pro, gpt-5.1-codex, gpt-5-codex, o3-deep-research, o4-mini-deep-research] 5종. Spring List 바인딩은 **대체**이므로 현 유효 리스트 = YAML 7종뿐.
- [확인] **`gpt-5.6`은 어느 리스트에도 없음** → gpt-5.6-luna/terra/sol은 responses-only로 오인되지 않고 chat-completions로 정상 라우팅된다. `ModelGuardSupport.isResponsesOnlyModel`은 `equals(p)|startsWith(p+"-")` — **`gpt-5.6`을 리스트에 추가하면 luna/terra/sol이 전부 로컬 gemma로 치환되어 Display 클라우드 전용 정책이 깨진다.** D1의 실제 지시는 "추가 금지 + 편집 시 Java 기본 5종 복원"이어야 한다.
- [확인] OpenAI cue 요청 계약 `LlmRouterAspect` L1204-1221: `tokenParamKey`→`maxCompletionTokens`, `reasoningEffort("low")`는 `gpt-oss`|`gpt-5.6` 시작 모델 한정(L1220), `serviceTier("default")`는 openai 한정(L1221). `ConversateApiRouteTest`가 이 계약을 고정.

### 3.2 Gemini 경로 (D4 관련)

- [확인] Gemini는 두 경로: (a) **native generateContent** — `GeminiGateway.generate()` L298-300 본문은 `contents`만(샘플링 필드 없음), `transcribeAudio()` L66-71은 `generationConfig{temperature:0,maxOutputTokens:1024,thinkingConfig{thinkingLevel:minimal}}`을 **SPEECH_MODEL=`gemini-3.5-flash-lite`**(L50)에만 전송. (b) **OpenAI-compatible 라우터 경로** — `LlmRouterAspect` L1070-1093 → `GeminiGateway.buildOpenAiCompatibleChatModel` L246-283.
- [확인] OpenAI-compat 경로는 `temperature/topP/frequencyPenalty/presencePenalty`를 non-null이면 **그대로 설정**(L252-263). `ModelCapabilities.sanitizeTemperature`의 rigid prefix는 `{gpt-5,o1,o3,o4}`뿐(L36-40) — **gemini-3.8-* 예외 없음**. meta-display에서 `gemini-pro` 라우트(`priced-model: gemini-3.8-flash`, application-meta-display.yml L88-95)를 타면 temperature 등이 Gemini 엔드포인트로 전송된다.
- [확인] cueJson 시 `reasoningEffort("low")`가 Gemini 빌더에 **무조건** 추가(L273-279). OpenAI-compat 표면에서 `reasoning_effort`는 thinking 수준 매핑으로 Google이 지원 — 3.8에는 이쪽이 맞고, 샘플링 필드 제거가 보완 대상.
- [정정] {스터프4} 지침 방향(필드 제거 + thinking_level)이 맞으나, **적용 범위는 OpenAI-compat 빌더 한 곳뿐** — native generateContent 경로는 이미 샘플링 필드를 보내지 않고, speech는 3.5-lite 대상이라 건드리면 안 됨.

### 3.3 오류 분류 (quota) — 실제 공백 발견

- [확인] `ConversateApiCueService.quotaExhausted` L498-507: `LlmGatewayException.reasonCode ∈ {insufficient_quota, credit_balance_exhausted, spend_limit_exceeded}` 또는 `HttpException 429` + 본문 error.code ∈ 동일 집합일 때만 true.
- [확인] `main/java` 전체에 `blocked_api_access` 문자열 **0건**, HTTP 400 처리 없음 — Groq 지출한도 차단(HTTP 400 + `blocked_api_access`)은 quotaExhausted로 분류되지 않아 **5분 계정 block(L152-153) 대신 일반 실패/재시도 경로**를 탄다. {스터프4} 지적이 소스에서 재현 확인 — **이번 취합의 유일한 신규 코드 결함.**
- [확인] Whisper STT 경로도 동일 가드 적용: `ConversateCloudStt` L213 `groqGuard.reserve("whisper-large-v3-turbo",...)`, L77 disabledReason 노출 — 애플리케이션 내부 통제는 있음(계정 무료 여부와는 별개).

### 3.4 Tavily / SerpAPI — 구현체별 정확한 상태

| 구현체 | 빈? | 실제 상태 [전부 확인] |
|---|---|---|
| `service.rag.TavilyWebSearchRetriever` | `@ConditionalOnProperty(tavily.enabled)` — `application-llm.yaml` L172 `${TAVILY_ENABLED:false}` + env 미설정 | **빈 미생성** (플래그 off) |
| `gptsearch.web.impl.TavilyProvider` | 무조건 `@Component`(L26) | 빈은 항상 존재하나 retriever 주입 실패 시 `doSearch`→빈 결과+`traceDisabled`(L29-30,45-48). "빈 없음"이 아니라 **"빈 있지만 내부 위임체 없음"**이 정확 |
| `agent.integrations.TavilyWebSearchRetriever` | 빈 아님 — `HybridRetriever`가 `new`로 수동 생성(L36/41) | RRF 도메인(`web+local`/`rrf` 또는 `RAG_USE_RRF=true`)에서만 호출 + `ProviderCredentialResolver.TAVILY` 자격증명 게이트(소스 L61-77). {스터프1} 라이브 테스트로 키·엔드포인트 유효 입증 |
| `abandonware.ai.integrations.TavilyWebSearchRetriever` | `@Component`로 **빈 등록됨** | `fetch()` 항상 `"[]"`(L7-9), 주입 지점 0건 — **죽은 빈**(호출자 없어 무해) |
| `gptsearch.web.impl.SerpApiProvider` | 무조건 `@Component`(L53) | `enabled` 기본 true(`gpt-search.serpapi.enabled:${search.serpapi.enabled:true}`, application.yml L367-368), 키 없으면 자기비활성(L99-119). 부팅 로그 키 로드 + {스터프1} 라이브 organic_results=5 |
| `acme CachedWebSearch` | `@Component` + `LangChainConfig` L884 수동 빈 | acme 포트 `WebSearchProvider`만 주입 — 구현체는 **Brave(priority 20)·Naver(5)뿐** |

- [정정] {스터프2} "SerpAPI가 AdaptiveWebSearchHandler 경로에서 현재 실제로 호출될 수 있음" → **부정확.** `SearchDecisionService` 기본 목록(L106-112)은 EN=[GOOGLECSE,TAVILY,NAVER], KO=[NAVER,TAVILY] — SerpAPI는 기본 목록에 없고, 명시적 `webProviders`/`providers` 메타 힌트가 있을 때만 도달한다.
- [정정] {스터프2} "Tavily 빈이 없으면 resolveProviders가 등록된 빈 전체로 폴백" → **오류.** `resolveProviders`(AdaptiveWebSearchHandler L408-447)는 `desired`가 비어 있을 때만 전체 빈 반환(L424-427); `decision.providers()`는 항상 ≥2개 기본값을 넣으므로 이 경로는 사실상 도달 불가. 요청된 제공자가 없으면 **스킵**(missing 기록)이지 전체 대체가 아니다. MockProvider가 운영 검색에 섞일 경로도 없음.
- [정정] {스터프2} "searchMulti(q,2)에서 SerpAPI 선택 가능성" → **불가.** searchMulti의 포트는 `com.acme.aicore.domain.ports.WebSearchProvider` — SerpApiProvider/TavilyProvider는 `gptsearch.web` 계열이라 이 리스트에 들어가지 않는다. `@Order`/`priority()` 추가도 무의미.
- [확인] `TAVILY_ENABLED=true` 한 줄 복구 주장은 **조건부로만 성립**: 빈 생성 조건 충족 외에 실행 프로세스 반영·키 유효(라이브 확인됨)·크레딧·비용 정책이 별도 확인 대상.

### 3.5 비활성 모델·타임아웃·토큰

- [확인] `application-llm.yaml` 기본 비활성: openai-premium `gpt-5.5`(L521/526), openai-balanced `gpt-5.4-mini`(L532/536), gemini-pro `gemini-2.5-pro`(L542/547), mistral-medium(L552), external `deepseek-v4-flash-free`(L573/577).
- [확인] **meta-display 프로필이 이를 덮어씀**: `application-meta-display.yml` L132-149에서 openai-economy/balanced/premium·gemini-pro를 `enabled:true` + 이름을 `gpt-5.6-luna/terra/sol`, `gemini-3.8-flash`로 재지정. 즉 base yaml의 구형 이름은 **meta-display 미적용 프로필에서만** 유효 — D3은 base yaml 한정·저우선순위.
- [확인] 12초 총한도는 코드상 보장: `answer()` L58-59가 `TimeBudgetContext`에 `total-timeout-ms`(12000, 범위 2000-20000) 데드라인 설치 → `call()` L368 `stageMs=min(남은 총예산, gate?3000:6500)` → L369 generationDeadline 설정 → L382 루프 조건 `nanoTime()<deadline` + L415 만료 시 TimeoutException. **6.5s×3=19.5s가 아니라 스테이지 데드라인과 총예산이 이중으로 자른다** — {스터프4}의 우려는 코드상 해소됨(변경 불요).
- [확인] `max-output-tokens: 640`(meta-display L36)과 612자 하드캡(`parseHint`)은 서로 다른 단위로 각각 적용. `max_completion_tokens`에 reasoning 토큰이 포함되는 것은 OpenAI 명세상 사실 — 현 예산은 보수적이며 **변경 지시 없음**(잘린 응답은 `GENERATION_INVALID_OUTPUT`으로 분류, L437).
- [확인] Groq 라우트 가드: `daily-budget-usd:0`(meta-display L52) + `ConversateCueRoutingPolicy` L66 `groq_free_tier_unverified` + `groq.free-tier.*` 증거/원장 경로(L182-186). 내부 통제는 존재 — "계정이 무료"라는 증거는 아님(별개 사실).

### 3.6 `demo.interview.enabled` — 문서 드리프트 확인

- [확인] `@Value` 기본값은 PageController L47·ConversateController L24·ChatOpenSecurityConfig L37 모두 `false`. **어떤 프로퍼티 파일도 true로 설정하지 않음** — `main/resources` 전체에서 flat key 0건, `application-meta-display.yml` L121-124만 nested로 `enabled: false` 명시.
- [정정] AGENTS.md "Current default `demo.interview.enabled=true`"와 `demo1-meta-display-webapp/references/source-contract.md`의 "`application.properties`에 =true" 기술은 **현 소스와 불일치** — 익명-first 설계 의도는 `/api/chat/sync` permitAll로 여전히 유지되지만 플래그 기술은 stale.
- [확인] 결과: live `/chat`·`/`·`/index`·`/chat-ui`는 `interviewDemo=false` 분기 → legacy `chat-ui` 템플릿(PageController L288-326). {스터프5}의 실행 관측과 일치.

### 3.7 기타 정정 (보고서 품질)

- [정정] `CONVERSATE_ASR_PROVIDER` set(len=6): `"local"`은 5글자 — 길이만으로 값 확정 불가. "설정됨, 실제 적용값 미확인"으로 기록. **자동 수정 금지**(코드 기본값 `local`, ConversateCloudStt L45 / ConversateAsrBridge L33).
- [정정] 18180 wear 런타임: 403 응답 단독 증거가 아니라 PROJECT_STATUS §3의 `Start-Meta-Display` run 152442 + pid 22756 ready + mgmt health UP (~15:45)가 근거.
- [정정] {스터프3} "테스트 계약 모두에서 재현 확인" → 존재 확인과 실행 통과는 구분. 이번 세션은 **정적 계약 확인 + 라인 재대조**만 수행, 테스트 미실행(검증 절차는 §5 지시서에 명시).
- [정정] {스터프1}/{스터프2} 본문 동일 → 독립 검증 2건이 아니라 **1건**.
- [정정] "미검증 잔여 = 전부 유료 호출 영역" → 공개 확인 가능 항목 다수 포함(§7 참조, {스터프4} §6 수용).

## 4. 소스수정 지시서 (적용 가능한 것만)

**공통 조건 — 모든 수정에 적용:**
- 작업 전 `demo1-source-edit-three-way-preflight` + 대상별 lease/preimage(`codex_work_checkpoint.py begin`) 필수. `main/java`·`main/resources` 변경은 JVM이 구 classpath를 유지하므로 **compile + DevWatch `[DEV-RELOAD] socket ready` 또는 ForceRestart** 후에야 live 증거로 인정. wear 런타임(pid 22756)은 비소유 — 재시작 금지.
- 유료 팬아웃·실호출 검증 금지(spend guard). 검증은 기존 테스트 + 합성 픽스처/로컬 HTTP만.
- 최소 diff. 관련 없는 코드·설정·테스트 계약 변경 금지. Git 상태를 복구 원천으로 쓰지 않음.

### E1 [GO·신규] Groq 지출한도 차단 분류 누락 수정

- **파일**: `main/java/com/example/lms/assist/ConversateApiCueService.java` L498-507 `quotaExhausted`
- **변경**: reason-code 집합에 `"blocked_api_access"` 추가 + 400(및 기타 상태)에서도 본문 error.code 일치 시 true. 429 전용 분기는 유지.
- **이유**: Groq spend-limit 차단이 HTTP 400+`blocked_api_access`로 오는데 현재 미분류 → `API_QUOTA_EXHAUSTED` 대신 일반 실패로 재시도 → 동일 계정 반복 호출 가능. `GENERATION_DENIED`/`API_QUOTA_EXHAUSTED` 시 5분 계정 block(L152-153)이 있는 설계 의도와 불일치.
- **범위 제한**: 이 predicate 하나만. 다른 제공자 분류·Whisper 경로·429 처리는 건드리지 않음.
- **검증**: 합성 Throwable로 `LlmGatewayException(reasonCode="blocked_api_access")` 및 `HttpException(400, body.error.code="blocked_api_access")` → `API_QUOTA_EXHAUSTED` 분류 확인 테스트 추가/확장(`ConversateApiCueServiceTest`). 기존 429 케이스 회귀 포함.

### E2 [GO·정정된 D4] Gemini 3.8 샘플링 필드 제거 (OpenAI-compat 경로 한정)

- **파일**: `main/java/com/example/lms/learning/gemini/GeminiGateway.java` L252-263 (`buildOpenAiCompatibleChatModel`)
- **변경**: `effective.model()`이 `gemini-3.8-`로 시작하면 `temperature/topP/frequencyPenalty/presencePenalty`의 builder 설정을 스킵. `reasoningEffort("low")`(L273-279)와 maxTokens 분기(L264-272)는 유지.
- **이유**: Google 2026-09-17 지침상 3.8은 temperature/top_p/top_k 제거 + thinking_level 사용({스터프4}). 현재 코드는 필드를 그대로 전송. 적용점은 이 빌더 한 곳 — native `generate()`/`transcribeAudio()`는 이미 무관하므로 건드리지 않음.
- **범위 제한**: `gemini-3.8-` prefix 한정. `gemini-3.5-flash-lite`(gemini-cue, SPEECH_MODEL) 및 다른 제공자 요청 변경 금지.
- **검증**: 빌더 호출을 캡처하는 단위 테스트 또는 기존 GeminiGateway 테스트 확장 — 3.8 모델에서 sampling 필드 미설정 + 3.5-lite에서 기존 동작 유지. `ConversateApiRouteTest` 회귀.

### E3 [GO·D5] `OpenAiTokenParamCompat` 주석 드리프트 교정 (주석만)

- **파일**: `main/java/com/example/lms/llm/OpenAiTokenParamCompat.java` L9-11, L87
- **변경**: "LangChain4j 1.0.1은 max_completion_tokens 미지원 → skip" 문구를 실제와 맞게 수정 — LC4j 1.0.1은 `maxCompletionTokens` 지원, `LlmRouterAspect` L1209가 실제 호출. `shouldSendLegacyMaxTokens` javadoc(L84-88)도 동일 교정.
- **이유**: 주석이 코드와 반대 — 향후 에이전트가 잘못된 제약으로 코드를 "수정"할 유인 제거.
- **검증**: `gradlew :compileJava -x test`. 테스트 불요(주석).

### E4 [선택·D-정리] 죽은 placeholder 빈 삭제

- **파일**: `main/java/com/abandonware/ai/integrations/TavilyWebSearchRetriever.java` (11행, `fetch()` 항상 `"[]"`)
- **변경**: 파일 삭제. 주입 지점 0건 확인됨(본 세션 grep) — 삭제 전 checkpoint begin 필수.
- **이유**: 무조건 `"[]"` 반환 빈 — 향후 혼선 제거. **나머지 Tavily(3종)는 유지**: service.rag는 fail-soft 런그, gptsearch 어댑터는 트레이스 계약(`web.tavily.*` 소비자 다수), agent 경로는 RRF 조건부 실사용.
- **검증**: `gradlew :compileJava -x test` + 관련 테스트(`TavilyProviderTest` 등) 통과.
- **조건부**: 이 항목은 순수 정리 — 생략해도 기능 영향 없음.

### E5 [GO·문서] `demo.interview.enabled` 기술 정정 (문서만)

- **파일**: `AGENTS.md` L16 부근 + `.agents/skills/demo1-meta-display-webapp/references/source-contract.md` L12
- **변경**: "default true" 기술을 실제와 맞게 — `@Value` 기본 false, `application-meta-display.yml`이 명시 false, **현재 어느 설정도 true를 켜지 않음**. 익명-first 정책 자체(`/api/chat/sync` permitAll, InterviewDemoFilter 조건부)는 그대로.
- **주의**: 플래그를 true로 되돌리는 것은 **제품 결정** — 이 지시서 범위 밖(§6 참조). 문서 정정만.
- **검증**: `scripts/verify_codex_instructions.ps1`.

### E6 [GO·문서] API_ROUTING_SPEC에 Display cue 예외 명시 (D6)

- **파일**: `docs/API_ROUTING_SPEC.md` §4 부근
- **변경**: 일반 RAG 로컬 우선(`configs/api-routing.yaml` policy.order)과 Display cue의 클라우드 전용(`ConversateCueRoutingPolicy` L218 `local()` 제외)이 **서로 다른 정책**임을 명시 — "코드가 이긴다"가 아니라 "표면이 다르다"로 기록.
- **검증**: 문서만 — `verify_codex_instructions.ps1` 관련 항목.

## 5. 지시하지 않는 것 (보류/기각 — 근거 포함)

| 항목 | 판정 | 근거 |
|---|---|---|
| D2 Responses 일괄 이관 | **기각** | 라이브 실패 증거 없음. Chat Completions 공식 지속 지원. `OpenAiResponsesChatModel`+ROUTE_RESPONSES 모드는 이미 존재 — 필요 시점에 해당 경로만 |
| `gpt-5.6`를 responses-only prefix에 추가 | **금지** | luna/terra/sol이 gemma4:26b로 치환 → Display 클라우드 정책 붕괴. D1의 정확한 내용은 "추가 금지" |
| D3 base yaml 비활성 모델명 정렬 | **보류(저가치)** | meta-display가 이름+enabled를 덮어씀(L132-149) — base yaml 이름은 비-meta-display 프로필에만 유효. 변경하려면 구체 이유부터 |
| Tavily `TAVILY_ENABLED=true` 활성화 | **보류** | 키는 유효하나 소비자 경로(AdaptiveWebSearchHandler 기본 목록은 TAVILY 포함) 외 비용 정책·잔여 크레딧 미확인. 요구 없으면 켜지 않음 |
| Tavily 전체 제거 | **기각** | 트레이스 계약·테스트 6종·agent 경로 연쇄 수정 — 리스크 대비 이득 없음 |
| SerpAPI searchMulti/`@Order`/우선순위 조정 | **기각** | searchMulti는 acme 포트(Brave/Naver)만 — SerpAPI 진입 불가 구조. AdaptiveWebSearchHandler는 명시 힌트 전용으로 의도된 설계 |
| 12초 총한도·640토큰/612자 변경 | **기각** | 총한도는 TimeBudgetContext+generationDeadline 이중 보장 확인(§3.5). 토큰/문자 상한은 각각 의도된 별개 제한 |
| Whisper/Groq 추가 가드 | **기각** | `groqGuard.reserve` 이미 적용(L213). E1의 분류 수정으로 충분 |
| `demo.interview.enabled` 값 변경 | **범위 밖** | 제품 결정 — {스터프5}의 /chat 재편 과제와 함께 별도 지시 필요 |
| {스터프5}의 /chat 사용자 프론트 재편 | **별도 과제** | 유효한 분석이나 이번 지시 범위 아님 — `assets/chat` 신설+PageController 분기는 독립 lease/검증 필요 |
| Gemini 3.5-lite speech `thinkingLevel:minimal` 변경 | **기각** | 대상 모델은 3.5-flash-lite — 3.8 규칙 적용 금지({스터프4}의 범위 제한과 일치) |
| reasoningEffort("low") 제거 | **기각** | OpenAI-compat 표면에서 Google이 지원하는 thinking 수준 매핑 — L1220은 gpt-oss/gpt-5.6 한정, L273-279는 cueJson 한정으로 이미 의도됨 |

## 6. 실행 순서와 검증

1. **E1**(quota 분류) → `gradlew :compileJava -x test` + `gradlew test --tests com.example.lms.assist.ConversateApiCueServiceTest` (+ 합성 400/`blocked_api_access` 케이스)
2. **E2**(Gemini 3.8) → compile + `ConversateApiRouteTest` + `ConversateCueRoutingPolicyTest` + `ConversateGatewayFailoverTest` 회귀
3. **E3**(주석) → compile 만
4. **E4**(선택 삭제) → compile + `TavilyProviderTest`·`SerpApiProviderTest`
5. **E5·E6**(문서) → `scripts/verify_codex_instructions.ps1`
6. 공통: Java 변경분은 DevWatch `[DEV-RELOAD] socket ready` 또는 대상 역할 ForceRestart 전까지 "built"까지만 주장 — wear live 반영은 소유 승인 별도.
7. `scripts/check-model-lock.ps1`로 모델 락 재확인(모델 미변경 지시이나 습관적 확인).
8. 각 사이클: `codex_work_checkpoint.py begin→seal→finish` + `work_journal.py note` + PROJECT_STATUS §4/§6 갱신.

## 7. 미검증 잔여 (정직한 한계)

| 항목 | 필요한 것 | 비용 |
|---|---|---|
| gpt-5.6-luna가 이 계정 키로 chat-completions 200 반환 | 유료 1회 호출 | 사용자 승인 필요 |
| meta-display 런타임의 1등 라우트 실측 | wear 라이브 진단(Debug-Meta-Display -Json 등) | 로컬 |
| `CONVERSATE_ASR_PROVIDER` 실제 값 | env 값 확인(비밀 출력 없이 허용값 일치 여부만) | 로컬 |
| SerpAPI Account API 잔여량 | 무료 계정 조회(월 할당 비차감) | 로컬 — 응답에 키/이메일 포함 가능, 상태·수치만 기록 |
| Gemini 3.8 OpenAI-compat가 sampling 필드 거부하는지 | E2 적용 후 합성 검증 또는 공식 compat 표 | 로컬 |
| `conversate.generation.enabled` 켜는 overlay 존재 | 활성 소스에는 기본 false만 확인 | 로컬 |

## 8. 보호 조건 (multi-agent 동시 수정 대비)

- wear 런타임(pid 22756)·Ollama·공유 프로세스 무접촉. 비소유 18180 서버 재시작 금지.
- 본 지시서의 E1-E6 외 파일을 "관련 있어 보임" 이유로 수정하지 않음. 각 항목의 범위 제한 준수.
- 다른 에이전트가 같은 파일을 수정 중이면 lease 충돌로 드러남 — `source_edit_session.ps1 -Action status -Json` 먼저 확인, 미기록 외부 변경 발견 시 덮어쓰기 중단.
- 이 문서는 지시서이지 실행 증거가 아님 — 각 항목은 적용 후 자체 검증으로만 완료 처리.

---

### 부록 A. 핵심 인용 인덱스

| 주장 | 위치 |
|---|---|
| responses-only 리스트(YAML 7종) | `application-llm.yaml` L367-374 |
| Java 기본 리스트(5종, 대체됨) | `NovaModelGuardProperties` L51-57 |
| prefix 매칭 규칙 | `ModelGuardSupport` L28-42 |
| OpenAI cue 계약 | `LlmRouterAspect` L1204-1221 |
| Gemini 라우트 분기 | `LlmRouterAspect` L1070-1093 → `GeminiGateway` L246-283 |
| quotaExhausted 공백 | `ConversateApiCueService` L498-507 |
| 12초 이중 한도 | `ConversateApiCueService` L58-59, L368-370, L382, L415 |
| Display 로컬 제외·선택식·cooldown | `ConversateCueRoutingPolicy` L218, L120-122, L148-153 |
| meta-display 라우트/가격/예산 | `application-meta-display.yml` L57-111, L130-169 |
| demo.interview.enabled 실제 값 | `application-meta-display.yml` L121-124; `@Value` 기본 `false` |
| Tavily 4종 상태 | §3.4 표 |
| SerpAPI enabled·searchMulti 경계 | `application.yml` L367-368; `CachedWebSearch` L42-47; `AdaptiveWebSearchHandler` L408-447 |
