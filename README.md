
# AbandonWare AI — Hybrid RAG Chat Backend (KR/EN)

> **KR**: 웹 검색 + 벡터 검색을 융합한 **하이브리드 RAG** 서버입니다. SSE 스트리밍, 메모리 강화 루프, 사실 검증 가드와 버전 **순도(LangChain4j 1.0.1)**를 강제합니다.
> **EN**: A production‑minded **hybrid RAG** backend fusing live web search and vector retrieval. Ships with SSE streaming, memory reinforcement, and evidence‑guarded answers. **LangChain4j 1.0.1 purity** enforced.

**Stack**: Java 17 · Spring Boot 3.x(WebFlux) · LangChain4j 1.0.1 · MariaDB/H2 · Redis(옵션) · ONNX Runtime(옵션)

---

## 0) Hiring‑Manager TL;DR

* **What**: 검색(네이버/옵션 DuckDuckGo) + 벡터DB(Pinecone/Upstash)를 **RRF+재랭킹(ONNX)**으로 결합한 RAG 백엔드
* **Why**: “즉시성(웹)”과 “정합성(문서벡터)”을 동시 달성, **근거(Evidence) 기반**으로 환각을 억제
* **How**: Self‑Ask → Hybrid Retrieval → Weighted‑RRF → (Bi‑Encoder → Cross‑Encoder) → Evidence Guard → SSE 스트리밍
* **Proofs**:

  * `/api/chat/stream` 실시간 스트림, `/api/probe/search` 체인 단계별 추적(관리자)
  * **Provider‑Guard**(필수 키 누락 시 즉시 실패), **LC4J 1.0.1 순도 검사** Gradle 태스크

> 바로 볼 포인트: `src_91/src/main/java/com/example/lms/api/ChatApiController.java` (SSE/세션) · `.../probe/SearchProbeController.java` (프로브) · `.../rag/fusion/WeightedRRF.java` (가중 RRF) · `.../plugin/image/ImageGenerationPluginController.java` (이미지 플러그인)

---

## 1) Highlights

### 하이브리드 검색 파이프라인

* **Self‑Ask 질의 변환** + 질의 위생
* **Web(Naver, opt: DuckDuckGo)** + **Vector(Pinecone/Upstash)** 동시 조회
* **RRF 융합 → ONNX/임베딩 재랭킹**(Bi‑Encoder 프리패스 후 Cross‑Encoder 정밀 재랭킹)
* 각 단계 **Fail‑Soft** 폴백: 인덱스/ONNX 부재 시 대체 경로 지속

### 프롬프트 규율

* 중앙 빌더: `PromptBuilder.build(ctx)` / `buildInstructions(ctx)`
* 섹션 자동 삽입: `### WEB EVIDENCE`, `### VECTOR RAG`, `### LONG-TERM MEMORY`
* 시스템 프롬프트는 `gpt.system.prompt` 프로퍼티에서 주입

### 사실 검증 & 가드

* `EvidenceAwareGuard`: **근거 없는 주장 차단**
* `FactVerifierService`: 사실 검증/증거 매핑/메타 질의

### 세션 & SSE 스트리밍

* `/api/chat/stream` **Server‑Sent Events** 스트림
* 세션 상태/목록/삭제 API 제공

### 메모리 강화 루프

* 스니펫 프루닝 + 보상 점수
* **볼츠만 에너지 기반 점진 강화**

### 한국어 최적화

* Lucene **Nori** 토크나이저
* 네이버 검색 **확장/차단/헤지 옵션**

### 버전 순도 게이트

* Gradle 태스크 `checkLangchain4jVersionPurity`로 **`dev.langchain4j:* = 1.0.1*` 고정**

---

## 2) What’s new in `src111_merge` (요약)

코드 베이스 분석 기준(**Java 1,200+ 파일, 다중 모듈**)으로 다음 기능군이 **추가/정리**되었습니다.

* **Search Probe(디버그) API**: `/api/probe/search` — 하이브리드 체인 리플리카 실행/추적(관리 토큰) 
* **페더레이티드 벡터 스토어**(Pinecone+Upstash): 토픽별 가중 라우팅 · `retrieval.vector.enabled=true` 토글 
* **Upstash 백드 웹 캐시 + RateLimiter**: 2계층 캐시(Caffeine→Upstash), `upstash.cache.ttl-seconds` 등 설정 
* **동적 리트리벌 오더링**(+ KG 핸들러 옵션): 질의 특성 따라 Web/Vector/KG 순서 자동 결정 
* **ONNX Cross‑Encoder 재랭킹**(로컬 추론) + **Bi‑Encoder 예비 재랭커**(속도형) 
* **Weighted‑RRF 퓨전**(멀티소스 안정 결합) 
* **도메인 화이트리스트/프로파일**(정부/학술/뉴스/게임 티어) 
* **요청 상관관계 트레이싱**(`X-Request-Id`, `X-Session-Id`) · **SSE 텔레메트리 퍼블리셔** 
* **네이버 검색 헤징/타임아웃/캐시** 품질·지연 제어 
* **적응형 번역(리액티브) API** `/api/adaptive-translate` + **번역 규칙 학습/관리 세트** 
* **n8n Webhook(HMAC) + 비동기 잡 큐** · **이미지 생성 플러그인** `/api/image-plugin` · **OCR 통합(테서랙트)** · **지식 큐레이션/인덱싱 스케줄러** · **권장 답변 안전장치** · **툴/플러그인 매니페스트** · **표준 컨텍스트 포맷 계약**

> 파일 경로 힌트: `src_91/src/main/java/com/example/lms/probe/SearchProbeController.java`,
> `.../plugin/image/ImageGenerationPluginController.java`,
> `.../rag/fusion/WeightedRRF.java`,
> `.../service/rag/handler/DynamicRetrievalHandlerChain.java`,
> `.../service/onnx/OnnxCrossEncoderReranker.java`

---

## 3) Quick Start

```bash
# 0) 순도/가드 점검
./gradlew check

# 1) 애플리케이션 실행 (WebFlux + SSE)
./gradlew bootRun

# (선택) application plugin 엔트리포인트
./gradlew run
```

* 기본 포트: `8080`
* **Secrets 커밋 금지**: API 키·토큰·비밀번호는 환경 변수 / Secret Manager로

### 샘플 설정 (`application.properties` / `application.yml`)

> values are placeholders; do not commit real secrets.

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

# Vector Store (Pinecone/Upstash)
pinecone.api.key=<YOUR_PINECONE_KEY>
...
upstash.vector.rest-url=<YOUR_UPSTASH_VECTOR_URL>
upstash.vector.api-key=<YOUR_UPSTASH_VECTOR_KEY>
upstash.redis.rest-url=<YOUR_UPSTASH_REDIS_URL>
upstash.redis.rest-token=<YOUR_UPSTASH_REDIS_TOKEN>
upstash.ratelimit.per-minute=60

# Reranker (ONNX)
abandonware.reranker.backend=embedding-model|onnx-runtime|noop
abandonware.reranker.onnx.model-path=classpath:/models/your-cross-encoder.onnx
abandonware.reranker.onnx.execution-provider=cpu|cuda

# Memory Reinforcement
memory.reinforce.score.low-quality-threshold=0.2
memory.snippet.min-length=30
memory.snippet.max-length=500
```

---

## 4) API Smoke Tests (cURL)

### (1) SSE 채팅 스트림

```bash
curl -N -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/chat/stream \
  -d '{"sid":"demo-1","message":"안녕!"}'
```

### (2) 동기식 채팅

```bash
curl -s -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/chat/sync \
  -d '{"sid":"demo-2","message":"서울 날씨 근거 포함 요약"}' -i
# 응답 헤더: X-Model-Used, X-RAG-Used
```

### (3) 세션 상태/관리

```bash
curl -s http://localhost:8080/api/chat/state
curl -s http://localhost:8080/api/chat/sessions
curl -s http://localhost:8080/api/chat/sessions/{id}
curl -X DELETE http://localhost:8080/api/chat/sessions/{id}
```

### (4) 이미지 생성 플러그인

```bash
curl -s -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/image-plugin/generate \
  -d '{"prompt":"A cute fox reading a book"}'
```

### (5) 검색 프로브(관리자)

```bash
curl -s -H "Content-Type: application/json" \
  -H "X-Admin-Token: <ADMIN_TOKEN>" \
  -X POST http://localhost:8080/api/probe/search \
  -d '{"q":"하이브리드 RAG 아키텍처","useWeb":true,"useRag":true}'
```

---

## 5) Endpoints

| Endpoint                     |     Method | Summary         |
| ---------------------------- | ---------: | --------------- |
| `/api/chat/stream`           |       POST | **SSE 스트림**     |
| `/api/chat/sync`             |       POST | 동기식 응답          |
| `/api/chat`                  |       POST | 비동기 요청          |
| `/api/chat/state`            |        GET | 세션 상태           |
| `/api/chat/sessions`         |        GET | 세션 목록           |
| `/api/chat/sessions/{id}`    | GET/DELETE | 세션 조회/삭제        |
| `/api/image-plugin/generate` |       POST | 텍스트→이미지         |
| `/api/image-plugin/jobs*`    |   POST/GET | 이미지 작업 큐/목록/상태  |
| `/api/probe/search`          |       POST | **검색 디버그(관리자)** |
| `/api/adaptive-translate`    |       POST | 적응형 번역(논블로킹)    |

> 구현 예: `ChatApiController`, `ImageGenerationPluginController`, `SearchProbeController`, `AdaptiveTranslateController`

---

## 6) Repository Structure (요약)

```
src_91/
├─ src/main/java/com/example/lms/
│  ├─ api/                         # ChatApiController, TranslateController
│  ├─ plugin/image/                # ImageGenerationPluginController, OpenAiImageService
│  ├─ probe/                       # SearchProbeController (+Service, DTO)
│  ├─ service/rag/                 # HybridRetriever, handlers, fusion, reranker
│  │   ├─ fusion/                  # RrfFusion, WeightedRRF
│  │   ├─ handler/                 # DynamicRetrievalHandlerChain, KnowledgeGraphHandler
│  │   └─ patch/                   # HybridRetriever (hardening)
│  ├─ service/onnx/                # OnnxRuntimeService, OnnxCrossEncoderReranker
│  ├─ service/ocr/                 # OcrService, BasicTesseractOcrService
│  ├─ controller/                  # AdaptiveTranslateController
│  └─ ...
└─ src/main/resources/
   ├─ application.yml(샘플 키 이름)
   └─ static/
```

---

## 7) Architecture (ASCII)

```
User (Web)
   │  SSE
   ▼
ChatApiController (/api/chat/*)
   ▼
ChatService
   ├─ PromptBuilder.build(ctx)     ← {web|rag|memory} null-safe
   ├─ ContextOrchestrator
   ├─ HybridRetriever
   │   ├─ QueryTransformer (Self‑Ask)
   │   ├─ Web Providers (Naver, opt DuckDuckGo)
   │   ├─ VectorDb (Pinecone / Upstash)
   │   ├─ RRF (Fusion) → Reranker (Bi‑Encoder → Cross‑Encoder/ONNX)
   │   └─ TopK + Dedup
   ├─ FactVerifierService + EvidenceAwareGuard
   ├─ LangChain4j ChatModel (Provider‑Guarded)
   └─ MemoryReinforcementService
   ▼
SSE Stream → Front‑end (chat.js)
```

---

## 8) Design Principles & Guardrails

* **PromptBuilder 전용**: 문자열 직접 결합 금지. `build(ctx)`/`buildInstructions(ctx)`만 사용
* **컨텍스트 주입**: `ctx.web/rag/memory/location/prev` 필드 기반
* **Provider‑Guard**: `llm.provider/base-url/api-key/chat-model` 누락 시 즉시 실패
* **Fail‑Soft**: 인덱스/ONNX 부재 시 대체 경로로 진행
* **Evidence‑First**: EvidenceAwareGuard + FactVerifierService

---

## 9) Troubleshooting

* **금지 의존성: `com.theokanning...`** → Gradle에서 제거
* **ONNX 미설정** → `abandonware.reranker.backend=embedding-model` 폴백
* **Vector 인덱스 비어있음** → 웹 검색만으로 응답(로그에서 Fail‑Soft 경로 확인)
* **프로브 비활성** → `probe.search.enabled=true` 필요
* **모델 오류** → `llm.chat-model` / `openai.chat.model.*` / `llm.api-key` 재확인

---

## 10) (옵션) **Agent Prompt Scaffold** — 시스템/특성 분리·머지 운영

**목표**: 에이전트 **시스템 프롬프트**와 **특성(Trait)** 지침을 분리 보관→런타임 머지. Markdown+YAML 기반 매니페스트/빌더 예시 포함. 폴더 스키마/매니페스트/빌드 명령 가이드라인은 아래 메모를 참조했습니다.

```
agent_scaffold/
  ├─ agents/resume_llm_mcp/
  │   ├─ system_ko.md
  │   └─ meta.yaml
  ├─ traits/stuff2_ko.md
  ├─ prompts.manifest.yaml
  └─ build.py / build.sh
```

* `prompts.manifest.yaml`의 `merge.order: [trait, system]`로 **Trait 우선 주입**
* `project_overrides_global` 충돌 정책으로 프로젝트 규칙 우선
* 빌드 예: `python build.py --manifest prompts.manifest.yaml --agent resume_llm_mcp`

---

## 11) (옵션) **추가기능 묶음(Jammini Memory — src111_merge15)**

운영지향·원자적 항목만 발췌(경로/토글 중심). 상세 목록은 프로젝트 메모를 참조했습니다.

* **Soak 테스트 API** `/internal/soak/run` — 쿼리셋 자동 샘플링/지표 집계
* **Search Probe** `/api/probe/search` — 체인 리플리카 실행/추적(관리자 토큰)
* **Federated Vector Store** — Pinecone+Upstash 가중 라우팅
* **Upstash‑backed Web Cache + RateLimiter** — 2계층 캐시
* **동적 Retrieval 오더링**(+ **KG 핸들러**)
* **ONNX Cross‑Encoder / Bi‑Encoder 재랭킹**
* **Weighted‑RRF Fusion**
* **Domain Allowlist Profiles**(정부/학술/뉴스/게임)
* **SSE Telemetry / Request Tracing**
* **Adaptive Translate API / Translation Rules**
* **n8n Webhook(HMAC) / 이미지 플러그인 / OCR / 스케줄러 / Answer Sanitizer / Tool Manifest / 표준 컨텍스트 포맷**

---

## 12) Resume‑Ready Bullets (복붙용)

* 설계: **웹+벡터 하이브리드 RAG 파이프라인**(Self‑Ask→RRF→Bi/Cross‑Encoder)로 **근거 기반** 응답 품질과 **추론 레이턴시 안정화** 구현
* 운영: **SSE 스트리밍** + 세션 관리 + **프로브/소크 테스트**로 **가시성/회귀** 확보
* 안전: **Evidence Guard** + **Provider‑Guard** + **도메인 화이트리스트**로 환각/취약 설정 차단
* 성능: **Upstash 캐시**·헤징·타임아웃으로 **실시간 검색 지연 분산**, 벡터 인덱스 부재시 **Fail‑Soft** 경로 유지
* 품질: 프롬프트 **중앙 빌더/머지** 규율로 **버전 순도(LC4J 1.0.1)** 보장

---

## 13) Acceptance Proof

```
DESKTOP_ONLY=YES
LC4J_PURITY=ONLY_1.0.1 (증빙: gradle/libs.versions.toml 또는 build.gradle)
CONFIG_IMMUTABLE=YES (설정 파일 미변경)
CHAIN_ORDER=Hybrid→SelfAsk→Analyze→Web→VectorDb (구성: src/main/java/.../RetrieverChainConfig.java)
PROMPT_RULE=PromptBuilder.build(ctx) ONLY (ChatService)
CONTENT_GUARD=EvidenceAwareGuard + FactVerifierService
OUTPUTS=*Core.zip only (exclude: .git/**, **/build/**, .gradle/**, .idea/**, out/**, .DS_Store)
```

---

## 14) License & Contributing

* 권장 라이선스: **MIT**
* PR 가이드: 시크릿 금지 · `PromptBuilder.build(ctx)` 규칙 준수 · **LangChain4j 1.0.1 고정** · Evidence/Facts 가드 우회 금지

**Maintainer**
Project: AbandonWare AI
Stack: Java 17, Spring Boot 3.x, WebFlux, LC4J 1.0.1, MariaDB/H2, Redis(opt)
Contact: *add your email*

---

## 15) Appendix — RRF & Rerank(개념)

* **Weighted‑RRF**: 여러 랭킹 리스트에 대해 `score = Σ w_i × 1/(K + rank_i)` 로 결합(K=60 권장)
* **재랭킹**: 빠른 **Bi‑Encoder** 1‑패스로 후보 필터링 → 정밀 **Cross‑Encoder(ONNX)** 2‑패스

---

> **Note for reviewers**: 샘플 cURL과 `/api/probe/search`만으로 체인 작동/증거 경로를 즉시 확인할 수 있습니다. 프롬프트/모델 키는 환경 변수로만 주입합니다.
