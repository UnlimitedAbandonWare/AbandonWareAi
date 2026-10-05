---
doc_id: TRI-CTX-06
title: Spring Boot 3.3.4 and LangChain4j 1.0.1 Platform Invariant SSOT
created_at: "2026-10-05T10:10:00+09:00"
expires_at: "PERPETUAL"
ttl_days: null
lifecycle: INVARIANT
validity_basis: "build.gradle.kts 의존성 및 checkLangchain4jVersionPurity 게이트, LlmConfig.java 라이브 소스"
---

# 06. Spring Boot 3.3.4 & LangChain4j 1.0.1 플랫폼 불변 SSOT (Tri-Agent 공유)

> 이 문서는 demo-1 체크아웃의 **빌드 고정 플랫폼 규격**이다. 웹서치 없이도
> Codex·Devin·Grok CLI·agy가 Spring Boot / LangChain4j 코드를 작성·검증할 수 있도록
> `build.gradle.kts`와 `main/java` 라이브 소스에서 확인한 사실만 모았다.
> 값이 바뀌는 모델·라우팅 레지스트리는 [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md)로 분리한다.

## 1. 플랫폼 베이스라인 및 의존성 매니페스트

모든 값은 `build.gradle.kts` 라이브 확인분이다.

- 언어/툴체인: **Java 17** + Gradle (`.\gradlew.bat`), Spring Boot **3.3.4**
- BOM: `implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))` (build.gradle.kts:92)
- Spring Boot 스타터 (build.gradle.kts:94–104):

| 스타터 | 용도 |
|---|---|
| `spring-boot-starter-web` | MVC/Tomcat — `/chat`·REST·SSE |
| `spring-boot-starter-webflux` | WebClient·Netty 리액티브 |
| `spring-boot-starter-aop` | LlmRouterAspect 등 오케스트레이션 AOP |
| `spring-boot-starter-data-jpa` | JPA/Hibernate |
| `spring-boot-starter-actuator` | /actuator 헬스·메트릭 |
| `spring-boot-starter-validation` | Bean Validation |
| `spring-boot-starter-security` | 필터 체인 (PROTO_OPEN 경계 — [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md)) |
| `spring-boot-starter-cache` | 캐시 추상화 |

- 회복탄력성: `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` (:109),
  `io.github.resilience4j:resilience4j-reactor:2.2.0` (:120)
- DB: `com.h2database:h2` **runtimeOnly** (:142)
  - 라이브 파일 DB URL (사실): `jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false`
    (`main/resources/application-meta-display.yml:7`, `scripts/db_agent.py:5`)
  - `AUTO_SERVER=TRUE`는 라이브 URL에 **없다** — 단일 소유 embedded H2이며,
    JVM 점유 중 직접 오픈은 lock(db_agent exit 3)이다. 유일 진입점 `scripts/db_agent.py`
    (01 문서 §H2, [H2_DATABASE_LOCKING_SPEC.md](../references/canonical-specs/H2_DATABASE_LOCKING_SPEC.md)).
  - `local` 등 다른 프로필은 `jdbc:h2:mem:*` — 재시작 시 증발.

## 2. LangChain4j 1.0.1 버전 순수성 계약 (Version Purity Gate)

- 허용 의존성은 정확히 2개뿐이다: `dev.langchain4j:langchain4j:1.0.1`,
  `dev.langchain4j:langchain4j-open-ai:1.0.1` (build.gradle.kts:134–135).
- 게이트 A — 선언 검사: `checkLangchain4jVersionPurity` (:150–160)가 모든
  configuration의 **선언된** `dev.langchain4j` 의존성을 훑어 버전이 `!= "1.0.1"`이면
  `GradleException`으로 빌드를 중단한다. `check` 태스크에 의존 등록됨 (:161).
- 게이트 B — 해상도 강제: `configurations.all { resolutionStrategy.eachDependency }` (:767–773)가
  **요청된** `dev.langchain4j` 버전이 1.0.1이 아니면 `useVersion("1.0.1")`으로 강제한다.
  추이 의존성도 1.0.1로 수렴하지만, 선언 자체가 다른 버전이면 게이트 A에서 실패한다.
- 금지 목록:
  - `langchain4j-spring-boot-starter` — 트리에 없으며, 추가하면 스타터 autoconfigure와
    추이 버전 오염으로 게이트가 실패한다. 빈은 `LlmConfig` 수동 등록이 정립 패턴.
  - 0.3x 버전군 전체 (`ChatLanguageModel` 시절 API 포함), `*-beta*` 버전군.
- 레거시: `main/java/com/example/lms/config/LangChain4jBeans.java`는
  `@ConditionalOnProperty(name="legacy.langchain4j-beans.enabled", havingValue="true")`로
  **기본 비활성**이다. canonical 빈은 `LlmConfig` 소유 — 레거시 활성화 시
  빈 정의 중복·override 순서 의존 문제가 되돌아온다.

## 3. LangChain4j 1.0.1 핵심 API 치트시트 (웹서치 불필요)

`main/java` 라이브 import에서 확인한 FQN만 기재한다 — `ChatLanguageModel` 사용은 0건.

### 채팅 모델

- 인터페이스: `dev.langchain4j.model.chat.ChatModel`
- 실행 메서드: `ChatResponse chat(ChatRequest request)`,
  `default String chat(String userMessage)`,
  `default ChatResponse chat(ChatMessage... messages)`, `chat(List<ChatMessage>)`
- 빌더: `OpenAiChatModel.builder().baseUrl(...).apiKey(...).modelName(...).temperature(...).timeout(...).build()`
  (`dev.langchain4j.model.openai.OpenAiChatModel`, LlmConfig.java:122 등)
- 메시지: `dev.langchain4j.data.message.{ChatMessage,UserMessage,AiMessage,SystemMessage,TextContent,ToolExecutionResultMessage}`
- 요청/응답: `dev.langchain4j.model.chat.request.{ChatRequest,ChatRequestParameters}`,
  `dev.langchain4j.model.chat.response.{ChatResponse,ChatResponseMetadata,StreamingChatResponseHandler}`
- JSON 구조화 출력: `ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(...)`
  — `dev.langchain4j.model.chat.request.{ResponseFormat,ResponseFormatType}`,
  스키마 `dev.langchain4j.model.chat.request.json.JsonSchema`
  (LlmRouterAspect.java:1721–1725, ConversateCardPrompt.java:95–96, GeminiGateway.java:323–327)
- HTTP 클라이언트 로더: `dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder()`
  (LangChainConfig.java:69, LlmRouterAspect.java:1672, ModelRuntimeHealthTracker.java:411)

### RAG·임베딩

- `dev.langchain4j.rag.content.Content`, `dev.langchain4j.rag.content.retriever.ContentRetriever`,
  `dev.langchain4j.rag.query.Query` — `ContentRetriever.retrieve(Query)` 패턴
  (WebSearchRetriever.java:25, UnifiedRagOrchestrator.java:2615–)
- `dev.langchain4j.data.segment.TextSegment` — `Content.from(TextSegment.from(text, Metadata.from(meta)))`
- `dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>` (LangChainConfig.java:268–),
  `dev.langchain4j.model.embedding.EmbeddingModel` (OllamaEmbeddingModel.java)

## 4. Spring Boot 빈 배선 패턴 (`com.example.lms.config.LlmConfig`)

정립된 ChatModel 빈 이름표 (라이브 확인):

| 빈 이름 | 선언 | 역할 |
|---|---|---|
| `chatModel` + `redChatModel` | `@Primary @Bean(name = {"chatModel","redChatModel"})` (:79–80) | 메인 채팅 모델 |
| `miniModel` | `@Bean(name = "miniModel")` (:159) | 소형/보조 |
| `fastChatModel` + `greenChatModel` | `@Bean(name = {"fastChatModel","greenChatModel"})` (:204) | fast 경로 |
| `exploreChatModel` | `@Bean(name = "exploreChatModel")` (:293) | 탐색/self-ask |
| `judgeChatModel` | `@Bean(name = "judgeChatModel")` (:351) | 심판/검증 |
| `highModel` | `@Bean(name = "highModel")` (:408) | 고성능 경로 |
| `localChatModel` | `@Bean(name = "localChatModel")` (:577) | `miniModel` 위임 로컬 경로 |
| `nightmareBreaker` | `@Bean` (:471) | 유틸 LLM 호출 회로차단기 |

- OpenAI 호환 엔드포인트 계약: `llm.base-url` (env `LLM_BASE_URL`)을 통해 Ollama
  `http://localhost:11434/v1` (application.yml:364), Vercel AI Gateway, DeepSeek 등을 공통 지원한다.
- 로컬 모델 라우팅: `assertGatewayAllowed` + `LocalLlmGatewaySecurity` 허용 호스트 점검,
  think=false 로컬은 `OllamaNativeChatModel`로 우회, 라우팅 정책 실패 시 `ExpectedFailureChatModel` fail-soft.
- 새 `ChatModel` 빈이 필요하면 `LlmConfig`에 같은 패턴으로 추가한다 — 별도 `@Configuration`
  신설 금지 (빈 정의 중복·override 순서 의존이 `LangChain4jBeans` 비활성화 사유).

## 5. 에이전트 할루시네이션 안티패턴 방어 (Zero-Search 가이드)

| 안티패턴 | 결과 | 정답 |
|---|---|---|
| 0.3x `ChatLanguageModel.generate()` 작성 | 컴파일 실패 (main/java 내 0건) | `ChatModel.chat(ChatRequest)` 계열 |
| `langchain4j-spring-boot-starter` 추가 | 순수성 게이트/버전 오염 | `LlmConfig` 수동 빈 등록 |
| 다른 `dev.langchain4j` 모듈·버전 선언 추가 | `checkLangchain4jVersionPurity` GradleException | 1.0.1 2개 아티팩트만 유지 |
| Python LangChain (`PromptTemplate.from_template`, `LLMChain`) 혼용 | 다른 생태계 문법 | Java builder/record 패턴 |
| Spring Boot 버전 상향·하향 제안 | BOM 정합 붕괴 | 3.3.4 고정 (변경은 사용자 명시 시에만) |
| H2 URL에 `AUTO_SERVER=TRUE` 추가 | 단일 소유 embedded 계약 파괴 | 라이브 URL 그대로 (`MODE=MariaDB;DATABASE_TO_UPPER=false`) |

## 6. 공식 참조 링크 및 오프라인 탐색 가이드

- Spring Boot 3.3.4 Reference: <https://docs.spring.io/spring-boot/docs/3.3.4/reference/html/>
- Spring Framework 6.1.x Reference: <https://docs.spring.io/spring-framework/reference/6.1/>
- LangChain4j 공식 문서: <https://docs.langchain4j.dev/>
- LangChain4j 1.0.1 릴리스 태그: <https://github.com/langchain4j/langchain4j/releases/tag/1.0.1>
- 오프라인 탐색 순서: (1) 이 문서 → (2) `rg -n "dev\.langchain4j" main/java`
  → (3) Gradle 캐시 아티팩트 `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\dev.langchain4j`
  → (4) `.\gradlew.bat dependencies --configuration runtimeClasspath` — 웹서치는 마지막 수단.

## 관련 문서

- [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md) — 루트 SSOT·PROTO_OPEN·H2 단일 접근
- [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md) — 가변 모델/라우팅 레지스트리
- [README.md](README.md) — 카탈로그 인덱스와 TTL 정책
