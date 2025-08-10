알겠습니다. 기존 README.md 내용에 제공해주신 상세한 수정 과정과 내역을 자연스럽게 통합하여 GitHub에 올리기 좋은 단일 문서로 만들어 드리겠습니다.

프로젝트의 개요와 아키텍처를 먼저 소개하고, 그 아래에 이 시스템이 어떻게 발전해왔는지를 보여주는 "개발 과정 및 주요 변경 내역" 섹션을 추가하는 것이 가장 이상적인 구조입니다.

최종 README.md
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
🚀 개발 과정 및 주요 변경 내역
이 시스템은 초기 버전의 한계를 극복하고 점진적으로 고도화되었습니다. 개발 과정은 크게 4단계로 요약할 수 있습니다.

1단계: 환각 긴급 대응: '에스코피에' 환각(AI가 존재하지 않는 것을 사실처럼 말하는 현상)을 막기 위해 쿼리 재작성, 사실 검증, RAG 프롬프트를 강화하여 잘못된 추론을 원천 차단했습니다.

2단계: 기능 구현 및 리팩토링: SSE 스트리밍('생각하는 기능'), 사용자 피드백('강화 기능')을 구현하면서 발생한 구조적 문제와 문법 오류를 해결했습니다.

3단계: API 불일치 해결: 대규모 리팩토링 이후 발생한 16개의 컴파일 오류를 해결했습니다. 이는 여러 서비스가 변경된 MemoryReinforcementService의 예전 메서드를 호출하고 있었기 때문이며, 호환성 유지를 위한 임시 메서드(Shim)를 추가하여 해결했습니다.

4단계: UX 고도화 (스마트 폴백): 단순히 "정보 없음"으로 응답하는 대신, 사용자의 의도를 파악하여 대안을 제시하는 지능형 응답 기능을 추가했습니다.

클래스별 상세 변경 내역
QueryDisambiguationService (1단계: 환각 1차 방어)

변경점: buildPrompt()의 프롬프트를 수정하고 clarify()에 사전 차단 로직을 추가했습니다.

상세: "게임에 존재하지 않는 고유명사가 있으면 질문을 재작성하지 말고 원본을 유지하라"는 규칙을 프롬프트에 추가했습니다. 또한 NonGameEntityHeuristics 유틸을 통해 "원신"과 "에스코피에"처럼 명백히 관련 없는 조합의 질문은 AI에게 보내기 전에 차단하도록 변경했습니다.

DefaultDomainTermDictionary (1단계, 4단계: 지식 강화)

변경점: DICTIONARY 맵 데이터를 보강했습니다.

상세: "원신" 카테고리에 '다이루크', '클로린드' 등을, "요리/인물"이라는 새 카테고리에 '에스코피에'를 등록하여 AI가 단어의 맥락을 더 잘 파악하도록 했습니다.

FactVerifierService (1단계: 환각 2차 방어)

변경점: verify() 로직, TEMPLATE 프롬프트, extractEntities 정규식 패턴을 수정했습니다.

상세: 답변의 핵심 단어들이 검색 결과에 실제로 존재하는지 확인하는 groundedInContext 로직을 추가하고, 검증 실패 시 **"정보 없음"**을 반환하도록 하여 안전성을 높였습니다.

ChatApiController (2단계: 기능 구현 및 리팩토링)

변경점: 클래스 구조를 개선하고 SSE 스트리밍 및 메시지 복원 로직을 안정화했습니다.

상세: chat() 메서드 내부에 잘못 위치했던 @PostMapping("/stream")을 클래스 레벨로 분리하여 컴파일 오류를 해결하고, 이전 대화 내용을 불러올 때 모델 정보나 검색 패널이 누락되지 않도록 메타데이터 파싱 로직을 개선했습니다.

MemoryReinforcementService (2단계, 3단계: 오류 해결 및 API 복구)

변경점: 오타를 수정하고, 대규모 리팩토링으로 인해 발생한 16개의 컴파일 오류를 해결하기 위해 **호환성 메서드(Shim)**를 추가했습니다.

상세: @Transactional 어노테이션 오타와 중복 정의된 applyFeedback 메서드를 수정했습니다. 가장 중요한 변경으로, reinforceWithSnippet, loadContext 등 여러 클래스에서 호출하던 예전 API를 임시로 복구하여 "cannot find symbol" 오류를 일괄 해결했습니다.

FeedbackController & chat.js (2단계: 기능 구현 및 오류 수정)

변경점: 피드백 API 호출부를 수정하고, 프론트엔드의 자바스크립트 문법 오류를 해결했습니다.

상세: FeedbackController가 MemoryReinforcementService의 applyFeedback 메서드를 올바른 인자와 함께 호출하도록 수정했습니다. chat.js에서는 HTML 템플릿 문자열의 따옴표 오류를 수정하고, 피드백 버튼(좋아요/싫어요)과 SSE 스트리밍 로직을 안정화했습니다.

ChatService (1단계, 4단계: RAG 강화 및 스마트 폴백 도입)

변경점: RAG_PREFIX 프롬프트를 강화하고, "스마트 폴백(Smart Fallback)" 로직을 도입했습니다.

상세: RAG 프롬프트에 "컨텍스트에 없는 존재를 주장하지 마라"는 규칙을 명시했습니다. 또한 FactVerifierService의 결과가 "정보 없음"일 때, 새로 추가된 SmartFallbackService를 호출하여 "에스코피에는 원신 캐릭터가 아닙니다. 혹시 다른 캐릭터를 찾으시나요?" 와 같이 더 유용한 안내를 제공하도록 개선했습니다.

신규 클래스 (4단계: 스마트 폴백 기능)

SmartFallbackService.java: "정보 없음" 상황에서 사용자의 의도를 교정해주는 더 나은 답변을 생성하기 위해 별도의 LLM을 호출하는 서비스입니다.

FallbackHeuristics.java: 사용자의 질문에서 "원신" 같은 도메인과 "에스코피에" 같은 비도메인 키워드를 탐지하고, '클로린드', '향릉'과 같은 적절한 대안을 제시하는 규칙 기반 유틸리티 클래스입니다.

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

abandonware:
  retrieval:
    mode: RETRIEVAL_ON      # RETRIEVAL_ON | RAG_ONLY | RETRIEVAL_OFF
    reranker: cross         # simple | cross
  session:
    metaKey: META_SID
  cache:
    caffeine:
      spec: "maximumSize=1000,expireAfterWrite=5m"

# 필수 환경변수: OPENAI_API_KEY, PINECONE_API_KEY, PINECONE_ENVIRONMENT, NAVER_API_*
▶️ 빠른 시작
Bash

# 1) 클론
git clone https://github.com/UnlimitedAbandonWare/AbandonWareAi.git
cd AbandonWareAi

# 2) 설정 템플릿 복사 및 키 입력
cp src/main/resources/application.yml.example src/main/resources/application.yml
vi src/main/resources/application.yml

# 3) 실행 (JDK 17+)
./gradlew bootRun
# 또는 IDE에서 LmsApplication.java 실행 → https://localhost:8080
🗂️ 프로젝트 구조 (요약)
Bash

src/main/java/com/example/lms
├─ api/              # API Controllers (Chat, Feedback, SSE)
├─ config/           # Bean/설정 (WebClientConfig, LangChainConfig, …)
├─ domain/           # JPA 엔티티 (LMS 관련)
├─ dto/              # 요청/응답 DTO (record 적극 활용)
├─ entity/           # JPA 엔티티 (AI/LLM 관련)
├─ repository/       # 데이터 접근
└─ service/
   ├─ rag/           # Retriever/Fuser/Reranker/RAG 서비스
   ├─ correction/    # 질의 교정 서비스
   ├─ reinforcement/ # 강화 학습 및 피드백 관련 서비스
   └─ ...
🤝 기여 가이드
저장소를 Fork → 브랜치 생성(feature/*) → 커밋 규칙(feat:, fix:, docs: …) 준수 → 테스트 포함 PR 생성. 아키텍처 변경 시 Mermaid 다이어그램 업데이트 부탁드립니다.

📄 라이선스
MIT License (LICENSE 참조)
