Got it. I'll combine the summary of code changes with the existing README.md content to create a comprehensive project overview suitable for GitHub.

Here is the updated README.md:

<a href="https://github.com/anuraghazra/github-readme-stats"> <img height="200" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /> </a>

(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

단순 LLM 호출을 넘어서 "스스로 생각하고(검색/추론) 사용자 피드백으로 발전하는(강화)" 지능형 RAG(검색 증강 생성) 시스템입니다. 실시간 웹 검색, 벡터 DB, 책임 연쇄(Chain of Responsibility) 패턴 기반의 검색 파이프라인, 재랭킹 및 2-Pass 검증을 통해 환각을 최소화합니다. 세션 격리, 캐싱, API 스트리밍, 동적 설정 등 운영 환경에 필수적인 기능들을 포함하고 있습니다.

📑 프로젝트 개요
실시간 웹 검색(Web), 형태소 분석 기반 검색(Analyze), 벡터 RAG(Vector/Pinecone)를 결합하여 신뢰도 높은 컨텍스트를 동적으로 구성합니다. 전체 시스템은 다음과 같은 단계적 파이프라인을 통해 지능형으로 작동합니다.

질의 향상 (Query Augmentation & Correction): 사용자의 불완전한 질문을 정제하고 확장하여 검색 품질을 극대화합니다.

심층 검색 및 결과 정제 (Deep Search & Refinement): 여러 소스에서 병렬로 정보를 수집하고, RRF(Reciprocal Rank Fusion)와 Cross-Encoder 재정렬을 통해 가장 관련성 높은 정보를 선별합니다.

검증 및 생성 (Grounded Generation): 생성된 답변의 내용이 검색된 근거에 실제로 존재하는지 교차 검증하여 환각을 억제합니다.

"생각하는 기능" (Streaming UX): SSE(Server-Sent Events)를 통해 AI의 처리 단계를 사용자에게 실시간으로 보여주어 대기 경험을 개선합니다.

"강화 기능" (Reinforcement Learning): 사용자의 좋아요/싫어요 피드백을 시스템의 장기 기억(Translation Memory)에 반영하여 점진적으로 성능을 개선합니다.

✨ 주요 기능 및 컴포넌트
범주	설명	핵심 컴포넌트
질의 향상	사용자의 오타, 구어체를 교정하고 검색 의도에 맞게 키워드를 동적 확장합니다.	LLMQueryCorrectionService, QueryAugmentationService, QueryComplexityGate
하이브리드 검색	Naver(웹), Lucene(형태소 분석), Pinecone(벡터 RAG)을 동시에 활용합니다.	HybridRetriever, NaverSearchService, AnalyzeWebSearchRetriever
결과 융합/재정렬	여러 소스의 검색 결과를 RRF 알고리즘으로 융합하고 Cross-Encoder로 재정렬합니다.	ReciprocalRankFuser, EmbeddingModelCrossEncoderReranker
2-Pass 검증	LLM 초안 답변을 생성한 뒤, 검색된 정보와 교차 검증하여 사실 기반 응답을 보장합니다.	FactVerifierService
실시간 스트리밍	SSE를 통해 AI의 처리 과정을 프론트엔드로 실시간 전송합니다. ("생각하는 기능")	ChatApiController (/stream), chat.js
강화 학습	사용자 피드백(👍/👎)을 시스템의 장기 기억 보상 점수에 반영합니다. ("강화 기능")	FeedbackController, MemoryReinforcementService
세션 관리	META_SID 메타데이터를 통해 모든 파이프라인에서 세션을 격리하고 Caffeine으로 캐싱합니다.	ChatHistoryService, PersistentChatMemory
고성능 통신	Netty(WebSocket), WebFlux(@Async/CompletableFuture)를 활용합니다.	NettyServerConfig, AdaptiveTranslationService
🧠 아키텍처 & 흐름
코드 스니펫

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
로그 분석 예시: 원신에 에스코피에랑 어울리는 조합
최근 로그에 기록된 SessionInfo[id=403, title=원신에 에스코피에랑 어울리는 조합이 ...]는 우리 시스템의 지능적 처리 과정을 잘 보여주는 사례입니다.

질의 의도: 사용자는 게임 **"원신(Genshin Impact)"**의 캐릭터 조합을 질문했습니다.

키워드 오인: "에스코피에"는 원신 캐릭터가 아니며, 불 속성 신규 캐릭터 **"클로린드(Clorinde)"**의 오타일 가능성이 높습니다.

시스템의 대응:

교정 (LLMQueryCorrectionService): "에스코피에"가 원신 컨텍스트에 맞지 않음을 인지하고 "클로린드"로 교정을 시도합니다.

검색 (HybridRetriever): 교정된 키워드 "원신 클로린드 조합"으로 웹과 벡터 DB에서 관련 정보를 수집합니다.

생성 (ChatService + LLM): "원신에는 '에스코피에'라는 캐릭터가 없습니다. 혹시 '클로린드'를 찾으시나요? 클로린드와 어울리는 조합은 다음과 같습니다..." 와 같이 사용자의 실수를 바로잡으며 정확한 정보를 제공하는 답변을 생성합니다.

⚙️ 설정 예시 (application.yml)
YAML

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
필수 환경변수: OPENAI_API_KEY, PINECONE_API_KEY, PINECONE_ENVIRONMENT (예: gcp-starter), NAVER_API_*

▶️ 빠른 시작
Bash

# 1) 클론
git clone https://github.com/<OWNER>/<REPO>.git
cd <REPO>

# 2) 설정 템플릿 복사 및 키 입력
cp src/main/resources/application.yml.example src/main/resources/application.yml
vi src/main/resources/application.yml

# 3) 실행 (JDK 17+)
./gradlew bootRun
# 또는 IDE에서 LmsApplication.java 실행 → https://localhost:8080
예시 요청 (스트리밍)
HTTP

POST /api/chat/stream
Content-Type: application/json
JSON

{
  "sessionId": "demo-1234",
  "message": "LangChain4j의 장점은 무엇인가요?",
  "useWebSearch": true,
  "useRag": true
}
🗂️ 프로젝트 구조 (요약)
Bash

src/main/java/com/example/lms
├─ api/             # API Controllers (Chat, Feedback, SSE)
├─ config/          # Bean/설정 (WebClientConfig, LangChainConfig, …)
├─ domain/          # JPA 엔티티 (LMS 관련)
├─ dto/             # 요청/응답 DTO (record 적극 활용)
├─ entity/          # JPA 엔티티 (AI/LLM 관련)
├─ repository/      # 데이터 접근
└─ service/
   ├─ rag/          # Retriever/Fuser/Reranker/RAG 서비스
   ├─ correction/   # 질의 교정 서비스
   ├─ reinforcement/# 강화 학습 및 피드백 관련 서비스
   └─ ...
🤝 기여 가이드
저장소를 Fork → 브랜치 생성(feature/*) → 커밋 규칙(feat:, fix:, docs: …) 준수 → 테스트 포함 PR 생성. 아키텍처 변경 시 Mermaid 다이어그램 업데이트 부탁드립니다.

📄 라이선스
MIT License (LICENSE 참조)
