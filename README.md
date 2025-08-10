<a href="https://github.com/anuraghazra/github-readme-stats"> <img height="200" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /> </a>
(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

단순 LLM 호출을 넘어서 웹 검색·분석·벡터 RAG를 통합하고, 재랭크→검증(2‑Pass) 으로 환각을 줄이는 하이브리드 RAG 오케스트레이터입니다. 세션 격리, 캐시, 안전가드까지 운영 관점에서 필요한 것들을 실제로 넣었습니다.

📑 프로젝트 개요
실시간 웹 검색(Web) + 분석 기반 검색(Analyze) + 벡터 RAG(Vector/Pinecone) 를 한 번에 묶어 신뢰도 높은 컨텍스트를 구성합니다.

HybridRetriever가 모든 리트리버의 결과를 취합하고 Rerank(간단 토큰/교집합 기반 → 필요 시 Cross)와 RRF Fuser로 정제합니다.

FactVerifierService가 초안 답변을 다시 한 번 사실 검증(2‑Pass) 하여 품질을 끌어올립니다.

META_SID 세션 메타데이터로 체인을 격리하고, Caffeine 캐시로 응답 속도를 개선합니다.

LLM Query Guardrail(별칭/사전/프롬프트 가드)로 고유명사 오교정을 방지합니다.

✨ 주요 개선 사항 (요약)
1‑Pass 통합 검색 파이프라인: ChatService가 HybridRetriever를 통해 한 번만 검색→LLM 호출. 중복 호출 제거.

세션 격리 & 공용 데이터 처리: META_SID 주입, RAG는 세션 일치/무세션/공용(*) 문서를 포함하도록 필터링.

검색 유연성 확보: WebSearchRetriever의 과도한 하드 코딩 필터 제거로 후보 폭 확장.

재랭킹 고도화: SimpleReranker(경량 교집합 기반) → 필요 시 Cross‑Encoder로 정밀 재정렬.

안전 가드: FactVerifierService + 공식 도메인 가중치 + 고유명사 보존(별칭/사전/프롬프트 가드).

🚀 주요 기능
범주	설명
하이브리드 검색	NaverSearchService(실시간 웹), AnalyzeWebSearchRetriever(형태소/키워드), Pinecone 기반 Vector RAG.
동적 라우팅	요청별 모드 전환: Retrieval ON(기본) / RAG only / Retrieval OFF.
2‑Pass 정제	LLM 초안 → FactVerifierService 추가검색·교차검증 → 최종 폴리시.
세션 캐싱	세션별 ConversationalRetrievalChain을 Caffeine으로 캐싱.
고성능 통신	Netty(WebSocket), WebFlux, @Async/CompletableFuture.
안전장치	민감 토픽/비공식 도메인 시 억제·보류, 공식 도메인 보너스 가중치.

🧠 아키텍처 & 흐름
mermaid
복사
flowchart TD
    U[User Request] --> R{Mode Routing<br/>(Retrieval ON | RAG only | Retrieval OFF)}

    R -->|Retrieval ON| HR(HybridRetriever)
    R -->|RAG only| RG[LangChainRAGService]
    R -->|Retrieval OFF| MC[memSvc.loadContext]

    subgraph HybridRetriever
      W[NaverSearchService<br/>Web Search] --> HR
      A[AnalyzeWebSearchRetriever] --> HR
      V[Vector RAG (Pinecone)] --> HR
    end

    HR --> RNK[Rerank (Simple / Cross) + RRF]
    RNK --> CTX[buildUnifiedContext]
    MC --> CTX
    RG --> OUT1[ChatResult]

    subgraph ChatService
      CTX --> LLM{LLM Call}
    end

    LLM --> D1[Draft Answer]
    D1 --> FV[FactVerifierService (2‑Pass Verification)]
    FV --> OUT2[Final Answer]
핵심 컴포넌트

HybridRetriever: Web/Analyze/Vector 결과 취합 → 재랭크 → 통합 컨텍스트.

ReciprocalRankFuser / Rerankers: 결과 융합 및 재순위화.

LangChainRAGService: Pinecone 인덱스 질의.

FactVerifierService: 생성 결과의 사실 검증.

META_SID 전파: 세션 격리/일관성 확보.

Caffeine Cache: 세션·쿼리 레벨 캐싱.

⚙️ 설정 예시 (application.yml)
yaml
복사
openai:
  api:
    key: "${OPENAI_API_KEY}"
    model: "gpt-4o"
    temperature:
      default: 0.7
    top-p:
      default: 1.0
    history:
      max-messages: 10
  web-context:
    max-tokens: 8000
  rag-context:
    max-tokens: 5000
  mem-context:
    max-tokens: 7500

pinecone:
  index:
    name: "my-knowledge-base"

search:
  official:
    domains: "company-blog.com,official-docs.com"

abandonware:
  retrieval:
    mode: RETRIEVAL_ON         # RETRIEVAL_ON | RAG_ONLY | RETRIEVAL_OFF
    reranker: cross            # simple | cross
  session:
    metaKey: META_SID
  cache:
    caffeine:
      spec: "maximumSize=1000,expireAfterWrite=5m"
필수 환경변수

OPENAI_API_KEY, PINECONE_API_KEY, PINECONE_ENVIRONMENT (예: gcp-starter), 필요 시 NAVER_API_*

▶️ 빠른 시작
bash
복사
# 1) 클론
git clone https://github.com/<OWNER>/<REPO>.git
cd <REPO>

# 2) 설정 템플릿 복사
cp src/main/resources/application.yml.example src/main/resources/application.yml
vi src/main/resources/application.yml  # 키/환경값 설정

# 3) 실행 (JDK 17+)
./gradlew bootRun
# 또는 IDE에서 Application.java 실행 → https://localhost:8080
예시 요청

http
복사
POST /api/chat
Content-Type: application/json
json
복사
{
  "sessionId": "demo-1234",
  "message": "LangChain4j의 장점은 무엇인가요?",
  "useWebSearch": true,
  "useRag": true
}
🗂️ 프로젝트 구조 (요약)
bash
복사
src/main/java/com/example/lms
├─ config/          # Bean/설정 (WebClientConfig, LangChainConfig, …)
├─ controller/      # API (ChatApiController, …)
├─ dto/             # 요청/응답 DTO (record 적극 활용)
├─ entity/          # JPA 엔티티
├─ repository/      # 데이터 접근
└─ service/
   ├─ rag/          # Retriever/Fuser/Reranker/RAG 서비스
   ├─ quality/      # FactVerifierService 등 품질 검증
   ├─ memory/       # TranslationMemory, Reinforcement
   └─ ...
🧪 트러블슈팅 (핵심 팁)
LangChain4j 버전 순도: 0.2.x/0.3.x와 1.0.x 아티팩트 혼재 금지(클래스패스 확인).

Prompt 생성 위치 단일화: ChatService 내 문자열 직접 결합 금지 → PromptBuilder.build(ctx) 사용.

세션 누수 방지: 모든 체인/저장소 호출에 META_SID 전파.

웹 검색 오탐: 필요 이상 도메인 하드필터링 지양, 공식 도메인 가중치로 안전성 확보.

🤝 기여 가이드
저장소를 Fork → 2) 브랜치 생성(feature/*) → 3) 커밋 규칙(feat:, fix:, docs: …) 준수 →

테스트 포함 PR 생성. 아키텍처 변경 시 Mermaid 다이어그램 업데이트 부탁!

📄 라이선스
MIT License (LICENSE 참조)
