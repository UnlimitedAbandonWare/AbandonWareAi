AbandonWare AI — Spring Boot RAG + Web Search + Memory

한·영 요약

한국어: 이 프로젝트는 Java 17과 Spring Boot 3.4로 구축된 하이브리드 RAG(검색-생성) 챗봇 서버입니다. 벡터 DB와 웹 검색을 혼합한 검색 파이프라인, LangChain4j 1.0.1 기반 LLM 연결, SSE 스트리밍 응답, 메모리 강화 루프, 사실 검증과 증거 기반 가드를 제공합니다. 모든 프롬프트는 PromptBuilder.build(ctx)를 통해 생성하며, LangChain4j 1.0.1 버전 순도를 강제로 검사합니다.

English caption: A hybrid retrieval‑augmented generation (RAG) backend built with Java 17 and Spring Boot 3.4. It fuses web search and vector DB retrieval, enforces LangChain4j 1.0.1 purity, streams responses via SSE, reinforces memory, and verifies facts with evidence‑aware guards. All prompts are built through a centralized PromptBuilder.

주요 기능 (Features)
하이브리드 검색 파이프라인 (Hybrid Retrieval)

Self‑Ask & Query Hygiene: HybridRetriever는 자가 질문 변환(Self‑Ask)과 질의 위생 검사를 통해 모호한 질문을 보충합니다.

웹 검색 & 벡터 RAG: Naver API(선택적으로 DuckDuckGo)를 이용한 웹 검색 후, Pinecone/Upstash 백엔드에서 벡터 세그먼트를 검색합니다. 검색 결과는 RRF(Reciprocal Rank Fusion)로 융합되며, ONNX 크로스 인코더 또는 임베딩 재랭커로 재정렬합니다.

Fail‑Soft: 벡터 인덱스가 비어 있거나 ONNX 모델이 없으면 웹 검색만 사용하거나 임베딩 재랭킹으로 폴백하는 등, 각 단계가 실패해도 다음 단계가 계속 실행됩니다.

프롬프트 규율 (Prompt Discipline)

중앙 프롬프트 빌더: 프롬프트는 PromptBuilder.build(PromptContext) 를 통해서만 생성합니다. 문자열을 직접 이어붙이는 행위는 금지되며, ctx.web/ctx.rag/ctx.memory 등 필드를 null‑safe로 주입합니다. 섹션별로 ### WEB EVIDENCE, ### VECTOR RAG, ### LONG‑TERM MEMORY 등이 자동 삽입되며, 시스템 지시문은 별도로 promptBuilder.buildInstructions(ctx)를 통해 추가합니다.

System Prompt 주입: 시스템 프롬프트는 gpt.system.prompt 속성으로 정의되며, 사용자가 모델을 변경해도 반드시 설정해야 합니다.

사실 검증·가드 (Fact Verification & Evidence‑Aware Guard)

EvidenceAwareGuard: 칩셋 주장과 같이 신뢰성이 요구되는 응답에서 뉴스나 공식 자료가 포함되어야 하며, 근거 없는 추측은 차단됩니다. Guard는 예외 규칙을 명시적으로 정의하고, 적절한 출처가 없으면 오류를 반환합니다.

FactVerifierService: 답변 생성 단계에서 LLM으로 사실 검증, 증거 매핑, 메타 질문 생성 등을 수행하여 거짓 정보를 제거합니다.

세션·스트리밍 (Sessions & Streaming)

SSE 스트리밍: /api/chat/stream 엔드포인트는 Server‑Sent Events를 통해 메시지를 스트리밍합니다. 요청에는 sid(세션 ID)와 message(사용자 질문)을 포함하며, 필요 시 attach 파라미터로 클라이언트 reconnect를 지원합니다.

세션 관리: /api/chat/sessions는 기존 세션을 목록화하며, 세션 ID로 조회/삭제가 가능합니다. /api/chat/state는 현재 실행 중인 세션 상태와 마지막 assistant 메시지, 사용 모델 등의 정보를 반환합니다.

메모리 강화 루프 (Memory Reinforcement)

Reward Scoring & Snippet Pruning: MemoryReinforcementService는 Reward Scoring Engine과 Snippet Pruner를 통해 저장된 대화 스니펫을 보상‑점수 기반으로 강화하고, 품질이 낮은 스니펫을 제거합니다. 프루닝 관련 속성으로 memory.reinforce.pruning.enabled, memory.reinforce.pruning.sentence-sim-threshold, memory.reinforce.pruning.min-sentences 등이 있습니다.

Boltzmann Energy: 강화 점수는 볼츠만 에너지 기반으로 계산되어 기억을 점진적으로 업데이트합니다.

한국어 최적화 (Korean Optimisations)

Lucene Nori Tokenizer: 웹/벡터 검색 시 Nori 토크나이저를 사용하여 한국어 품질을 향상합니다.

도메인·별칭 필터: 네이버 검색에서 특정 도메인 차단, 제품/폴드/반전 키워드 확장 등 한국어 검색 품질을 높이는 다양한 속성을 제공합니다.

Fail‑Soft 및 폴백 정책 (Fail‑Soft & Fallbacks)

벡터 인덱스가 비어 있을 때는 웹 검색만으로 응답을 생성하며, ONNX Reranker 미설정 시 임베딩 기반 재랭커로 자동 폴백됩니다.

기본 모델 또는 프로바이더를 누락하면 OpenAI로 묵시적 폴백하지 않고 즉시 실패하도록 구현되어 있습니다. 운영 환경에서는 반드시 llm.provider, llm.base-url, llm.api-key, llm.chat-model을 명시해야 합니다.

버전 순도 게이트 (Version Purity Gate)

Gradle checkLangchain4jVersionPurity 태스크는 모든 dev.langchain4j 모듈이 1.0.1인지 검증하며, 혼합 버전이 발견되면 컴파일을 중단합니다. 충돌 목록은 build/reports/langchain4j-conflicts.txt에 기록됩니다.

아키텍처 다이어그램 (Architecture Diagram)

아래 ASCII 다이어그램은 요청 흐름과 주요 컴포넌트를 나타냅니다. 영어 캡션은 간략한 해설입니다.

User(Web)
   │  (SSE)
   ▼
ChatApiController (/api/chat/*)
   ▼
ChatService
   ├─ PromptBuilder.build(ctx)  ← {web|rag|memory} null‑safe
   ├─ ContextOrchestrator
   ├─ HybridRetriever
   │   ├─ QueryTransformer (Self‑Ask)
   │   ├─ Web Providers (Naver, optional DuckDuckGo)
   │   ├─ VectorDbHandler (Pinecone / Upstash)
   │   ├─ RRF (Fusion) → Reranker (ONNX | Embedding)
   │   └─ TopK + Deduplication
   ├─ FactVerifierService + EvidenceAwareGuard
   ├─ LangChain4j ChatModel (Provider‑guarded)
   └─ MemoryReinforcementService (reinforceWithSnippet)
   ▼
SSE Stream → Front‑end (chat.js)


English caption: Requests flow from the web client to the ChatApiController, pass through the ChatService where prompts are built, retrieval happens, facts are verified and memory reinforced, and responses are streamed back via SSE.

빠른 시작 (Quickstart)
요구사항 (Requirements)

JDK 17 및 Gradle 8.x

데이터베이스: 기본 DB는 MariaDB이며, 개발 환경에서는 H2를 선택할 수 있습니다. 데이터베이스 스키마는 JPA 엔티티에 따라 자동 생성됩니다.

Redis (선택): 캐시와 토큰 버킷(rate limiter)에 사용됩니다.

빌드 및 실행 (Build & Run)
# (wrapper가 없으면 'gradle' 사용)
./gradlew check           # LangChain4j 순도 검사와 플레이스홀더/오프라인 가드 포함
./gradlew bootRun         # Spring Boot(WebFlux + SSE) 실행
# 또는
./gradlew run             # application plugin 엔트리 포인트

환경/프로퍼티 설정 예시 (Property Examples)

다음 설정은 application.yml/application.properties에서 정의할 수 있는 키들입니다. 실제 키나 토큰 값을 코드/문서에 넣지 마십시오; 아래는 키 이름만 표시한 예시입니다.

# LLM
llm.provider=groq|openai|vertex|anthropic
llm.base-url=https://api.groq.com/openai/v1
llm.api-key=<YOUR_LLM_API_KEY>
llm.chat-model=llama-3.1-8b-instant
llm.chat.temperature=0.2
llm.high.model=gpt-5-chat-latest  # MOE용 고품질 모델

# System Prompt
gpt.system.prompt=### INSTRUCTIONS: Synthesize answers from sources (higher authority first)...

# Web Search (Naver)
naver.search.client-id=<YOUR_NAVER_ID>
naver.search.client-secret=<YOUR_NAVER_SECRET>
naver.search.display=20
naver.search.web-top-k=10
naver.search.rag-top-k=5
naver.search.query-suffix=뉴스
naver.search.query-sim-threshold=0.7
naver.search.blocked-domains=example.com,bad.co
naver.search.timeout-ms=5000
naver.search.expansion-policy=auto
naver.search.product-keywords=…
naver.search.fold-keywords=…
naver.search.flip-keywords=…
naver.search.fusion=rrf  # none|rrf
naver.search.similar-threshold=0.8
naver.hedge.enabled=true
naver.hedge.timeout-ms=2000
naver.hedge.delay-ms=800
naver.fallback.duckduckgo.enabled=true

# Vector Store (Pinecone)
pinecone.api.key=<YOUR_PINECONE_KEY>
pinecone.environment=<YOUR_ENV>
pinecone.project.id=<YOUR_PROJECT>
pinecone.index.name=<YOUR_INDEX>
pinecone.namespace=<OPTIONAL_NAMESPACE>

# Vector Store (Upstash)
upstash.vector.rest-url=<YOUR_UPSTASH_VECTOR_URL>
upstash.vector.api-key=<YOUR_UPSTASH_VECTOR_KEY>
upstash.vector.namespace=<OPTIONAL_NAMESPACE>
upstash.redis.rest-url=<YOUR_UPSTASH_REDIS_URL>
upstash.redis.rest-token=<YOUR_UPSTASH_REDIS_TOKEN>
upstash.ratelimit.per-minute=60

# Reranker (ONNX)
abandonware.reranker.backend=embedding-model|onnx-runtime|noop
abandonware.reranker.onnx.model-path=classpath:/models/your-cross-encoder.onnx
abandonware.reranker.onnx.execution-provider=cpu|cuda
abandonware.reranker.onnx.vocab-path=classpath:/models/vocab.txt
abandonware.reranker.onnx.max-seq-len=384
abandonware.reranker.onnx.max-chars=3500
abandonware.reranker.onnx.normalize=true
abandonware.reranker.onnx.fallback-enabled=true

# Memory Reinforcement
memory.reinforce.score.low-quality-threshold=0.2
memory.snippet.min-length=30
memory.snippet.max-length=500
memory.reinforce.pruning.enabled=true
memory.reinforce.pruning.sentence-sim-threshold=0.8
memory.reinforce.pruning.min-sentences=3
memory.reinforce.pruning.llm.enabled=false

# Ranking & Fusion
retrieval.fusion.rrf.weights.w_ce=0.6
retrieval.fusion.rrf.weights.w_bm25=0.3
retrieval.fusion.rrf.weights.w_sem=0.1
ranking.rrf.k=60
ranking.rerank.ce.topK=20

# Router (MOE)
router.moe.tokens-threshold=1200
router.moe.complexity-threshold=0.55
router.moe.uncertainty-threshold=0.40
router.moe.web-evidence-threshold=0.60
router.moe.escalate-on-rigid-temp=true
router.moe.learning-intents=CURATION,RULE_INDUCTION,KB_UPDATE,ANALYSIS
openai.chat.model.a-default=gpt-5-mini
openai.chat.model.moe=gpt-5-chat-latest

# Miscellaneous
abandonware.web.trace.expose=true
memory.read.max-turns=15
memory.evidence.max-turns=10



⚠️ 실제 키·토큰 값은 코드에 커밋하지 마십시오. 모든 프라이빗 키는 별도의 환경 변수나 시크릿 매니저에 설정해야 합니다.

API 개요 (API Overview)

아래 표는 주요 엔드포인트와 기능을 요약합니다. 각 API는 JSON 요청/응답을 처리하며, SSE 스트리밍은 웹 클라이언트(EventSource)에서 메시지를 실시간으로 수신합니다.

엔드포인트	메서드	요약
/api/chat/stream	POST	SSE 스트리밍 응답. sid(세션 ID)와 message를 보내면, 챗봇이 단계적으로 답변을 전송합니다. attach=true로 재연결 시 기존 세션에 추가로 연결할 수 있습니다.
/api/chat/sync	POST	동기식 완결 응답. 한 번에 모든 응답을 받고, 응답 헤더에 X-Model-Used와 X-RAG-Used가 포함됩니다.
/api/chat	POST	비동기식 채팅 요청. 요청이 바로 반환되고, 백그라운드에서 세션이 생성됩니다.
/api/chat/state	GET	현재 실행 중인 세션의 상태, 마지막 assistant 메시지, 사용 모델, RAG 사용 여부를 확인합니다.
/api/chat/sessions	GET	기존 세션 ID 목록을 반환합니다.
/api/chat/sessions/{id}	GET	특정 세션의 대화 기록과 메타데이터를 조회합니다.
/api/chat/sessions/{id}	DELETE	세션을 제거하여 메모리에서 삭제합니다.
/api/image-plugin/generate	POST	텍스트 프롬프트로 이미지를 생성합니다. 이미지 API 키가 없으면 500 오류를 반환합니다.
/api/image-plugin/jobs	POST	이미지 생성 비동기 작업을 큐에 넣고 ETA를 반환합니다.
/api/image-plugin/jobs	GET	최근 이미지 생성 작업 목록을 조회합니다.
/api/image-plugin/jobs/{id}	GET	특정 작업의 상태를 조회합니다.
/api/probe/search	POST	검색 파이프라인을 디버깅하기 위한 프로브. admin token이 필요하며, query와 옵션을 받아 웹+벡터 검색 결과와 메타데이터를 반환합니다.
/api/location/consent/{onOff}	POST	사용자 위치 동의 상태를 켜거나 끕니다. X-User-Id를 통해 사용자 식별자가 전달됩니다.
/api/location/events	POST	GPS 좌표와 타임스탬프를 포함하는 위치 이벤트를 전송합니다. 사전에 동의가 되어 있어야 합니다.
/api/audio/tts	POST	plain text를 받아 MP3 음성 파일을 반환합니다.
/api/audio/stt	POST	음성 파일을 업로드하면 텍스트로 변환하여 반환합니다.
기타		/api/chat/cancel(채팅 취소), /api/image-plugin/jobs 리스트 외에도 다양한 관리 엔드포인트가 존재합니다.
SSE 응답 예시 (Chat Stream)

클라이언트는 다음과 같이 SSE를 구독할 수 있습니다. JavaScript EventSource를 이용하면 스트림을 수신할 수 있습니다.

const source = new EventSource('/api/chat/stream');
source.addEventListener('message', (e) => {
  const event = JSON.parse(e.data);
  console.log(event.role, event.content);
});
source.addEventListener('error', () => {
  source.close();
});

// 메시지 전송은 별도의 fetch 호출로 수행
await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ sid: 'demo-1', message: '안녕!' })
});


서버는 Assistant 메시지, 내부 진행 상황, 오류 등을 이벤트 스트림으로 전송합니다. 클라이언트는 SSE 연결을 유지하며, data: 필드에 JSON payload가 전달됩니다.

설정 키 목록 (Configuration Keys)

이 섹션은 코드에 등장하는 주요 환경 변수/프로퍼티 이름들을 정리한 표입니다. 값은 절대 포함하지 않습니다.

카테고리	키 이름들 (비포괄)
LLM	llm.provider, llm.base-url, llm.api-key, llm.chat-model, llm.high.model, llm.chat.temperature, gpt.system.prompt
웹 검색(Naver)	naver.search.client-id, naver.search.client-secret, naver.search.display, naver.search.web-top-k, naver.search.rag-top-k, naver.search.query-suffix, naver.search.query-sim-threshold, naver.search.blocked-domains, naver.search.timeout-ms, naver.search.expansion-policy, naver.search.product-keywords, naver.search.fold-keywords, naver.search.flip-keywords, naver.search.fusion, naver.search.similar-threshold, naver.hedge.enabled, naver.hedge.timeout-ms, naver.hedge.delay-ms, naver.fallback.duckduckgo.enabled
벡터 저장소	pinecone.api.key, pinecone.environment, pinecone.project.id, pinecone.index.name, pinecone.namespace; upstash.vector.rest-url, upstash.vector.api-key, upstash.vector.namespace; upstash.redis.rest-url, upstash.redis.rest-token, upstash.ratelimit.per-minute
Reranker / Fusion	abandonware.reranker.backend, abandonware.reranker.onnx.model-path, abandonware.reranker.onnx.execution-provider, abandonware.reranker.onnx.vocab-path, abandonware.reranker.onnx.max-seq-len, abandonware.reranker.onnx.max-chars, abandonware.reranker.onnx.normalize, abandonware.reranker.onnx.fallback-enabled, retrieval.fusion.rrf.weights.w_ce, retrieval.fusion.rrf.weights.w_bm25, retrieval.fusion.rrf.weights.w_sem, ranking.rrf.k, ranking.rerank.ce.topK
메모리	memory.reinforce.score.low-quality-threshold, memory.snippet.min-length, memory.snippet.max-length, memory.reinforce.pruning.enabled, memory.reinforce.pruning.sentence-sim-threshold, memory.reinforce.pruning.min-sentences, memory.reinforce.pruning.llm.enabled, memory.read.max-turns, memory.evidence.max-turns
라우터 & 모델	router.moe.tokens-threshold, router.moe.complexity-threshold, router.moe.uncertainty-threshold, router.moe.web-evidence-threshold, router.moe.escalate-on-rigid-temp, router.moe.learning-intents, openai.chat.model.a-default, openai.chat.model.moe
기타	abandonware.web.trace.expose, naver.search.debug, probe.search.enabled, ranking.rerank.ce.weight, ranking.rerank.ce.use-semantic, 등
설계 원칙 & 가드레일 (Design Principles & Guardrails)
프롬프트 생성 규칙 (Prompting Policy)

PromptBuilder 전용: 프롬프트는 PromptBuilder.build(ctx) 및 buildInstructions(ctx)로만 생성합니다. 문자열 결합(+, \n)을 통한 즉흥적인 프롬프트 조립은 금지됩니다.

컨텍스트 주입: RAG, 웹 증거, 메모리, 위치, 이전 응답 등 모든 컨텍스트를 PromptContext에 필드로 담아 프롬프트 빌더에 전달합니다. 빌더는 순서를 보존하며 ### MUST_INCLUDE 섹션에 중요한 키워드를 추출합니다.

System Prompt 관리: 시스템 프롬프트는 gpt.system.prompt 속성에서 불러오며, 코드에 하드코딩하지 않습니다.

프로바이더 가드 (Provider Guard)

모델·프로바이더 명시: llm.provider, llm.base-url, llm.api-key, llm.chat-model을 반드시 설정해야 합니다. 기본값이 없으며, 누락 시 서비스는 즉시 실패하여 OpenAI로 자동 폴백하지 않습니다.

Reranker 백엔드: abandonware.reranker.backend 값이 onnx-runtime인데 모델 파일이 없으면 임베딩 기반 재랭킹으로 자동 폴백합니다. noop 설정 시 재랭킹을 비활성화합니다.

Fail‑Soft: 벡터 인덱스가 비어 있거나 외부 API 오류가 발생해도 웹 검색 등 다른 수단으로 최대한 응답을 생성합니다.

세션 및 트레이스 (Session & Trace)

상관관계 로깅: sid와 x-request-id 헤더를 전파하여 로그에서 요청을 추적할 수 있도록 합니다. SSE 스트림에는 세션 ID가 유지됩니다.

Security: 기본 admin 계정/비밀번호는 환경 배포 전에 반드시 교체하십시오. 설정 파일에 기본 자격 증명이나 시크릿을 커밋하지 마십시오.

검증 기반 응답: FactVerifierService와 EvidenceAwareGuard를 통해 검증된 정보만 반환하도록 합니다.

버전 순도 및 품질 체크 (Purity & Quality Checks)

LangChain4j 순도: 모든 dev.langchain4j:* 모듈이 1.0.1인지 확인하는 checkLangchain4jVersionPurity 태스크를 사용합니다.

플레이스홀더 탐지: failOnPlaceholders 태스크는 TODO/STUB 미완성 코드를 탐지하여 빌드 실패를 유도합니다.

오프라인 리플레이 가드: offlineReplayCheck 태스크는 로그 리플레이 공격과 캐시 오염에 대한 방어 기능을 점검합니다.

의존성/클래스패스 덤프: emitDependencyReport와 emitClasspathJars를 통해 빌드의 의존성 그래프와 클래스패스를 분석할 수 있습니다.

트러블슈팅 (Troubleshooting)

Forbidden dependency detected: com.theokanning… → 레거시 OpenAI 클라이언트가 혼입되었습니다. Gradle 의존성에서 제거하십시오.

ONNX 미설정 → abandonware.reranker.backend=embedding-model로 설정하여 임베딩 기반 재랭커로 폴백합니다.

Vector 인덱스 비어있음 → 벡터 DB에 세그먼트가 없을 때는 웹 검색만으로 진행합니다. 로그에서 Fail‑soft 동작을 확인하십시오.

프로브 미사용 → /api/probe/search 엔드포인트는 기본적으로 비활성화되어 있으며, probe.search.enabled를 true로 설정해야 사용 가능합니다.

모델 오류 → llm.chat-model 또는 openai.chat.model.* 값이 맞는지 확인하고, llm.api-key를 올바르게 설정했는지 점검합니다.

데모 & 스모크 테스트 (Demo & Smoke Test)

다음 예제는 로컬 서버에서 기본 기능을 검증하는 간단한 테스트입니다.

SSE 채팅 테스트
curl -N -H "Content-Type: application/json" \
     -X POST http://localhost:8080/api/chat/stream \
     -d '{"sid":"demo-1","message":"안녕!"}'

이미지 생성 테스트
curl -X POST http://localhost:8080/api/image-plugin/generate \
     -H "Content-Type: application/json" \
     -d '{"prompt":"A cute fox reading a book"}'

검색 프로브 테스트
curl -X POST http://localhost:8080/api/probe/search \
     -H "Content-Type: application/json" \
     -H "X-Admin-Token: <ADMIN_TOKEN>" \
     -d '{"q":"원신 스커크 최신 패치 노트"}'


각 테스트는 성공적인 응답을 반환해야 합니다. 실패할 경우 로그와 환경 설정을 확인하십시오.

저장소 구조 (Repository Structure)

프로젝트는 모듈화된 패키지 구조를 따릅니다. 아래는 주요 디렉터리의 요약입니다.

src/
├─ build.gradle            # 프로젝트 빌드 스크립트, LangChain4j BOM 및 purity 태스크 정의
├─ docs/                   # 의존성 트리 등 문서
└─ main/
   ├─ java/com/example/lms/
   │   ├─ api/                 # REST 컨트롤러 (ChatApiController, ImageGenerationPluginController 등)
   │   ├─ service/rag/         # HybridRetriever, 핸들러 체인, RRF fuser, reranker
   │   ├─ service/reinforcement/  # MemoryReinforcementService, RewardScoringEngine
   │   ├─ service/vector/      # Pinecone 및 Upstash 어댑터
   │   ├─ service/guard/       # EvidenceAwareGuard
   │   ├─ service/answer/      # AnswerExpanderService, FactVerifierService
   │   ├─ prompt/              # PromptBuilder, PromptContext, SystemPrompt
   │   ├─ config/              # AppSecurityConfig, WebClientConfig, PineconeProps, MoeRoutingProps
   │   ├─ search/              # NaverSearchService 등 검색 구현
   │   └─ ...                  # 기타 서비스 및 도메인 로직
   └─ resources/
       ├─ application.yml      # 기본 설정 예제 (키 이름만 포함)
       └─ static/              # 프론트엔드 자산

라이선스 / 기여 (License & Contributing)

라이선스가 아직 명시되지 않았다면 MIT 라이선스를 채택하는 것을 권장합니다. PR을 제출할 때는 다음 규칙을 준수하십시오:

시크릿 금지: API 키, 토큰, 비밀번호 등 민감 정보는 커밋하지 마십시오.

프롬프트 규칙 유지: PromptBuilder.build(ctx)를 사용하며 문자열 직접 결합을 피합니다.

LangChain4j 버전 고정: 모든 dev.langchain4j 모듈은 1.0.1을 유지합니다. 버전 변경이 필요한 경우 먼저 BOM을 업데이트하고 checkLangchain4jVersionPurity 태스크를 수정하십시오.

안전 검증: FactVerifierService와 EvidenceAwareGuard를 우회하는 코드는 허용되지 않습니다. 증거 기반 응답 원칙을 준수하십시오.

본 README는 실제 소스 코드와 빌드 스크립트를 분석하여 작성되었습니다. 설명된 설정과 가이드라인을 따르면 하이브리드 RAG 챗봇을 빠르게 실행하고 안정적으로 운영할 수 있습니다.
