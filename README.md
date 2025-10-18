# README

AbandonWare AI — Spring Boot RAG + Web Search + Memory
*(Java 17 · Spring Boot 3.4 · LangChain4j 1.0.1 Purity Enforced)*

> **KR**: 웹 검색과 벡터 DB를 융합한 하이브리드 RAG 챗봇 서버입니다. SSE 스트리밍, 메모리 강화 루프, 사실 검증 가드를 제공합니다. 모든 프롬프트는 `PromptBuilder.build(ctx)`로만 생성되며 LangChain4j **1.0.1 순도**를 강제합니다.
> **EN**: A hybrid RAG backend that fuses web search with vector retrieval, streams via SSE, reinforces memory, and guards answers with evidence checks. Prompts are built centrally via `PromptBuilder.build(ctx)` and LC4J **1.0.1 purity** is enforced.

---

## 핵심 기능 (Highlights)

* **하이브리드 검색 파이프라인**

  * Self-Ask 변환 & 질의 위생
  * 웹 검색(Naver, opt: DuckDuckGo) + 벡터 DB(Pinecone/Upstash)
  * RRF 융합 → ONNX/임베딩 재랭킹
  * 각 단계 **Fail-Soft** 폴백

* **프롬프트 규율**

  * 중앙 빌더: `PromptBuilder.build(PromptContext)` / `buildInstructions(ctx)`
  * 섹션 자동 삽입: `### WEB EVIDENCE`, `### VECTOR RAG`, `### LONG-TERM MEMORY`
  * 시스템 프롬프트는 `gpt.system.prompt` 속성에서 주입

* **사실 검증 & 가드**

  * `EvidenceAwareGuard`: 근거 없는 주장 차단
  * `FactVerifierService`: 사실 검증/증거 매핑/메타 질의

* **세션 & 스트리밍**

  * SSE `/api/chat/stream` 스트림 응답
  * 세션 상태/목록/삭제 API 제공

* **메모리 강화 루프**

  * 보상 점수 & 스니펫 프루닝
  * 볼츠만 에너지 기반 점진 강화

* **한국어 최적화**

  * Lucene **Nori** 토크나이저
  * 네이버 검색 확장/차단/헤지 옵션

* **버전 순도 게이트**

  * Gradle `checkLangchain4jVersionPurity` 태스크로 **dev.langchain4j:* = 1.0.1*\* 강제

---

## 필수 사전 준비 (Prerequisites)

* **JDK**: 17
* **Gradle**: 8.x (래퍼 포함)
* **DB**: MariaDB (개발: H2 선택 가능)
* **옵션**: Redis (캐시/토큰 버킷), ONNX 런타임 모델 파일

> ⚠️ **시크릿 커밋 금지**: API 키/토큰/비밀번호 값은 절대 저장소에 커밋하지 마세요. 환경 변수나 시크릿 매니저를 사용하세요.

---

## 빠른 시작 (Quick Start)

```bash
# 1) 순도/가드 점검
./gradlew check

# 2) 애플리케이션 실행 (WebFlux + SSE)
./gradlew bootRun

# (선택) application plugin 엔트리포인트
./gradlew run
```

기본 포트: `8080`

---

## 설정 (Configuration Examples)

*values are placeholders; do not commit real secrets.*

```properties
# LLM
llm.provider=groq|openai|vertex|anthropic
llm.base-url=https://api.groq.com/openai/v1
llm.api-key=<YOUR_LLM_API_KEY>
llm.chat-model=llama-3.1-8b-instant
llm.chat.temperature=0.2
llm.high.model=gpt-5-chat-latest

# System Prompt
gpt.system.prompt=### INSTRUCTIONS: Synthesize answers from sources...

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
naver.search.fusion=rrf
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

# Misc
abandonware.web.trace.expose=true
memory.read.max-turns=15
memory.evidence.max-turns=10
```

---

## API 빠른 테스트 (cURL)

### 1) SSE 채팅 스트림

```bash
curl -N -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/chat/stream \
  -d '{"sid":"demo-1","message":"안녕!"}'
```

### 2) 동기식 채팅

```bash
curl -s -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/chat/sync \
  -d '{"sid":"demo-2","message":"서울 날씨 근거 포함 요약"}' \
  -i
# 응답 헤더에 X-Model-Used, X-RAG-Used 포함
```

### 3) 세션 상태/관리

```bash
# 현재 상태
curl -s http://localhost:8080/api/chat/state

# 세션 목록
curl -s http://localhost:8080/api/chat/sessions

# 특정 세션 조회/삭제
curl -s http://localhost:8080/api/chat/sessions/{id}
curl -X DELETE http://localhost:8080/api/chat/sessions/{id}
```

### 4) 이미지 생성

```bash
curl -s -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/image-plugin/generate \
  -d '{"prompt":"A cute fox reading a book"}'
# 이미지 API 키 미설정 시 500
```

### 5) 검색 프로브 (Admin)

```bash
curl -s -H "Content-Type: application/json" \
  -H "X-Admin-Token: <ADMIN_TOKEN>" \
  -X POST http://localhost:8080/api/probe/search \
  -d '{"q":"원신 스커크 최신 패치 노트"}'
```

---

## 엔드포인트 요약

| Endpoint                      |     Method | Summary     |
| ----------------------------- | ---------: | ----------- |
| `/api/chat/stream`            |       POST | SSE 스트림     |
| `/api/chat/sync`              |       POST | 동기식 응답      |
| `/api/chat`                   |       POST | 비동기 요청      |
| `/api/chat/state`             |        GET | 세션 상태       |
| `/api/chat/sessions`          |        GET | 세션 목록       |
| `/api/chat/sessions/{id}`     | GET/DELETE | 세션 조회/삭제    |
| `/api/image-plugin/generate`  |       POST | 텍스트→이미지     |
| `/api/image-plugin/jobs`      |   POST/GET | 이미지 작업 큐/목록 |
| `/api/image-plugin/jobs/{id}` |        GET | 작업 상태       |
| `/api/probe/search`           |       POST | 검색 디버그(관리자) |

> 표는 **키워드만** 포함합니다. 상세 설명은 상단 섹션과 소스 주석을 참고하세요.

---

## 저장소 구조 (Repository Structure)

```
src/
├─ build.gradle                     # LC4J BOM & purity task
├─ docs/
└─ main/
   ├─ java/com/example/lms/
   │  ├─ api/                      # ChatApiController, ImageGenerationPluginController
   │  ├─ service/rag/              # HybridRetriever, RRF, Reranker
   │  ├─ service/reinforcement/    # MemoryReinforcementService
   │  ├─ service/vector/           # Pinecone/Upstash adapters
   │  ├─ service/guard/            # EvidenceAwareGuard
   │  ├─ service/answer/           # FactVerifierService, AnswerExpander
   │  ├─ prompt/                   # PromptBuilder, PromptContext, SystemPrompt
   │  ├─ config/                   # Security/WebClient/Props
   │  └─ search/                   # NaverSearchService, DuckDuckGo
   └─ resources/
      ├─ application.yml           # 샘플 키 이름만
      └─ static/                   # 프론트 자산
```

---

## 설계 원칙 & 가드레일

* **PromptBuilder 전용**: 문자열 직접 결합 금지. `build(ctx)`/`buildInstructions(ctx)`만 사용
* **컨텍스트 주입**: `ctx.web/rag/memory/location/prev` 필드 기반
* **Provider Guard**: `llm.provider/base-url/api-key/chat-model` 누락 시 즉시 실패 (OpenAI 폴백 없음)
* **Fail-Soft**: 인덱스/ONNX 부재 시 대체 경로로 진행
* **검증 우선**: EvidenceAwareGuard + FactVerifierService

---

## 트러블슈팅 (Troubleshooting)

* **Forbidden dependency: `com.theokanning...`**
  레거시 OpenAI 클라이언트입니다. Gradle 의존성에서 제거하세요.
* **ONNX 미설정**
  `abandonware.reranker.backend=embedding-model` 로 폴백.
* **Vector 인덱스 비어있음**
  웹 검색만으로 응답. 로그의 Fail-Soft 경로 확인.
* **프로브 비활성**
  `probe.search.enabled=true` 설정 필요.
* **모델 오류**
  `llm.chat-model` / `openai.chat.model.*` / `llm.api-key` 확인.

---

## 데모 & 스모크 테스트

```bash
# SSE
curl -N -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/chat/stream \
  -d '{"sid":"demo-1","message":"안녕!"}'

# 이미지
curl -s -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/image-plugin/generate \
  -d '{"prompt":"A cute fox reading a book"}'

# 검색 프로브 (관리자)
curl -s -H "Content-Type: application/json" \
  -H "X-Admin-Token: <ADMIN_TOKEN>" \
  -X POST http://localhost:8080/api/probe/search \
  -d '{"q":"하이브리드 RAG 아키텍처"}'
```

---

## TODO

* **데이터/DB**

  * 대화 로그 스키마 최적화
  * 증거 캐시 만료 정책
* **프론트/GUI**

  * SSE 뷰어 & 세션 타임라인
  * 증거 하이라이트 UI
* **파일/유틸**

  * 검색 소스별 노이즈 필터 강화
  * Nori 토큰화 튜닝
* **보안/가드**

  * 관리자 토큰 롤링
  * 위치 이벤트 서명 검증
* **엔진 고도화**

  * 대용량 세그먼트 배치 인덱싱
  * 리랭커 A/B 및 CE 가중 자동화

---

## 아키텍처 (ASCII)

```
User(Web)
   │  (SSE)
   ▼
ChatApiController (/api/chat/*)
   ▼
ChatService
   ├─ PromptBuilder.build(ctx)  ← {web|rag|memory} null-safe
   ├─ ContextOrchestrator
   ├─ HybridRetriever
   │   ├─ QueryTransformer (Self-Ask)
   │   ├─ Web Providers (Naver, optional DuckDuckGo)
   │   ├─ VectorDbHandler (Pinecone / Upstash)
   │   ├─ RRF (Fusion) → Reranker (ONNX | Embedding)
   │   └─ TopK + Dedup
   ├─ FactVerifierService + EvidenceAwareGuard
   ├─ LangChain4j ChatModel (Provider-guarded)
   └─ MemoryReinforcementService
   ▼
SSE Stream → Front-end (chat.js)
```

---

## 라이선스 & 기여

* 권장 라이선스: **MIT**
* PR 가이드:

  * 시크릿 금지
  * `PromptBuilder.build(ctx)` 규칙 준수
  * **LangChain4j 1.0.1 고정** (BOM/순도 태스크 동반 수정)
  * EvidenceAwareGuard/FactVerifierService 우회 금지

---

## Maintainer

* **Project**: AbandonWare AI
* **Stack**: Java 17, Spring Boot 3.4, WebFlux, LC4J 1.0.1, MariaDB/H2, Redis(옵션)
* **Contact**: *add your email here*

---

## ACCEPTANCE\_PROOF

```
DESKTOP_ONLY=YES
LC4J_PURITY=ONLY_1.0.1 (혼입 없음; 증빙: gradle/libs.versions.toml 또는 build.gradle)
CONFIG_IMMUTABLE=YES (설정 파일 미변경)
CHAIN_ORDER=Hybrid→SelfAsk→Analyze→Web→VectorDb (구성: src/main/java/.../RetrieverChainConfig.java)
PROMPT_RULE=PromptBuilder.build(ctx) ONLY (ChatService: src/main/java/.../ChatService.java:LINE)
CONTENT_GUARD=no direct Content impl / no d.id() (호출부: N/A 또는 파일경로)
DESKTOP_OK_TOKEN=DESKTOP_OK (존재 확인; ZIP에는 제외)
OUTPUTS=*Core.zip only (exclude: .git/**, **/build/**, .gradle/**, .idea/**, out/**, .DS_Store)
```

