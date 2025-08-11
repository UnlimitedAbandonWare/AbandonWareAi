(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
<a href="https://github.com/anuraghazra/github-readme-stats"><img height="180" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /></a>

Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI 고정)

단순 LLM 호출을 넘어, 실시간 웹 검색 + 벡터 DB + 재랭킹 + 2-Pass 검증으로 스스로 생각하고 사용자 피드백으로 강화되는 지능형 RAG 시스템입니다. 세션 격리, 캐싱, SSE 스트리밍, 동적 설정 등 운영 필수 기능을 기본 제공합니다.

📑 프로젝트 개요
하이브리드 검색: 실시간 웹(Web/Naver), 형태소 분석(Analyze), 벡터 RAG(Pinecone)를 동시·선택적 결합하여 신뢰도 높은 컨텍스트를 구성합니다.

결과 융합/재정렬: 다원 소스 RRF/Softmax 융합 → (Simple|Cross) Re-rank → Authority 가중으로 환각(Hallucination)을 억제합니다.

강화 & 메타-학습: 👍/👎 피드백을 **전략 성적표(StrategyPerformance)**로 축적하고, Softmax(볼츠만 탐색) 기반으로 다음 질의에서 최적 전략을 확률적으로 탐색/선택합니다.

실시간 UX: 처리 과정을 **SSE(Server-Sent Events)**로 스트리밍하여 “생각하는 과정”을 가시화합니다.

✨ 주요 기능 및 컴포넌트
범주	설명	핵심 컴포넌트
질의 향상	오타/구어 교정, 의도 기반 키워드 동적 확장	LLMQueryCorrectionService, QueryAugmentationService, QueryComplexityGate
하이브리드 검색	Naver(웹), Analyze(형태소), Pinecone(벡터 RAG)	HybridRetriever, NaverSearchService, AnalyzeWebSearchRetriever
결과 융합/재정렬	다원 소스 RRF/Softmax 융합 + Cross-Encoder 재정렬 + Authority 가중	ReciprocalRankFuser, SoftmaxUtil, EmbeddingModelCrossEncoderReranker, AuthorityScorer
2-Pass 검증	Meta-Check(주제 일치) → Fact-Check(근거 교차검증)	FactVerifierService
실시간 스트리밍	처리 단계 SSE 전송(“생각하는 기능”)	ChatApiController(/stream), chat.js
강화 학습	피드백을 보상 점수로 반영(“강화 기능”)	FeedbackController, MemoryReinforcementService
세션 관리	META_SID 기반 파이프라인 세션 격리 + 캐싱	ChatHistoryService, PersistentChatMemory, Caffeine
고성능 통신	Netty/WebFlux 비동기	NettyServerConfig
메타 강화 학습	전략 자가 학습/선택, 시스템 파라미터 자동 튜닝	StrategySelectorService, ContextualScorer, DynamicHyperparameterTuner, StrategyPerformance
🧠 아키텍처 & 흐름
1) 검색·생성 파이프라인 (안전장치/신뢰도 반영)
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
    HR --> RNK[RRF / Softmax<br/>+ (Simple | Cross) Re-rank]
    RNK --> AUTH[Rank by Authority Score]
    AUTH --> CTX[Build Unified Context]
    MC --> CTX
    RG --> CTX
    subgraph ChatService
      CTX --> LLM{LLM Call}
    end
    LLM --> D1[Draft Answer]
    D1 --> FV[Meta-Check & Fact-Check]
    FV --> FB{Smart Fallback?}
    FB -- N --> OUT[Final Answer]
    FB -- Y --> SUGGEST[Suggest Alternatives]
2) 메타-학습 & 전략 선택 루프 (자가 진화)
코드 스니펫

flowchart TD
    subgraph User Interaction
        U[User Request] --> ChatService
    end
    subgraph "Meta-Learning & Strategy"
        style "Meta-Learning & Strategy" fill:#f9f9f9,stroke:#ddd,stroke-dasharray: 5 5
        SP[(StrategyPerformance DB)]
        HT(DynamicHyperparameterTuner) -.->|Tune| Params[(Hyperparameter DB)]
    end
    subgraph ChatService
        SS(StrategySelectorService) -- Reads --> SP
        ChatService -- "1. 어떤 전략?" --> SS
        SS -- "2. 최적 전략 반환" --> R{Dynamic Routing}
        R -- "전략 A" --> HR(HybridRetriever)
        R -- "전략 B" --> RG[RAG-Only]
        R -- "전략 C" --> MC[Memory-Only]
        HR --> CTX[Build Unified Context]
        RG --> CTX
        MC --> CTX
        CTX --> LLM{LLM Call}
        LLM --> Answer[Final Answer]
    end
    subgraph "Reinforcement Loop"
        style "Reinforcement Loop" fill:#e8f4ff,stroke:#aed6f1
        Answer --> Feedback[User Feedback (👍/👎)]
        Feedback --> CS(ContextualScorer)
        CS -- "다차원 평가(Factuality/Quality/Novelty)" --> MRS(MemoryReinforcementService)
        MRS -- "기억 강화" --> TM[(TranslationMemory DB)]
        MRS -- "전략 성과 기록" --> SP
    end
🚀 개발 과정 & 주요 변경 내역
환각 긴급 대응: 쿼리 재작성, 사실 검증, RAG 프롬프트 강화를 통해 잘못된 추론을 차단했습니다.

핵심 기능 구현: SSE 스트리밍(생각하는 기능)과 사용자 피드백(강화 기능)을 안정적으로 운영할 수 있도록 구현했습니다.

컴파일 오류 해결 & 구조 개선: MemoryReinforcementService의 API를 정리하고(Shim 유지), DB 쿼리를 TranslationMemoryRepository로 이관했으며, 누락된 의존성 및 중복 선언을 정리했습니다.

UX 고도화 (스마트 폴백): "정보 없음" 대신 사용자의 의도를 추정하고 대안을 제시하는 기능을 추가했습니다.

메타 강화 루프 도입 (시스템 자가 진화):

전략적 행동 선택: StrategySelectorService가 과거 성과에 기반하여 성공률 높은 검색 전략을 동적으로 선택합니다.

다차원 성과 측정: ContextualScorer가 사실성, 품질, 정보가치(신규성)를 종합 평가하여 보상(Reward)을 고도화합니다.

자동 파라미터 튜닝: DynamicHyperparameterTuner가 탐험/활용 비율, 가중치 등 하이퍼파라미터를 주기적으로 조정합니다.

DynamicChatModelFactory 도입: 런타임에 모델, 온도(temperature) 등 파라미터를 동적으로 설정할 수 있도록 개선했습니다.

RestTemplateConfig 개선: Spring Boot 3.x 기준에 맞춰 deprecated된 API를 최신 connectTimeout/readTimeout 설정으로 교체했습니다.

🧩 도메인 & 서비스 (신규 포함)
신규 엔티티: StrategyPerformance
strategyName: WEB_FIRST, VECTOR_FIRST, DEEP_DIVE_SELF_ASK, WEB_VECTOR_FUSION 등

queryCategory: "제품 스펙 문의", "단순 사실 질문" 등

successCount, failureCount: 성공 및 실패 횟수

averageReward: 피드백 기반 평균 점수

용도: FeedbackController → MemoryReinforcementService를 통해 전략 성적을 집계하고 업데이트합니다.

신규 서비스
StrategySelectorService: 질의 특징(길이, 키워드, 의도)과 StrategyPerformance 통계를 기반으로 최적 전략을 선택하며, ChatService가 HybridRetriever 호출 전에 사용합니다.

ContextualScorer:

Factuality: FactVerifierService로 RAG 근거의 충실도를 평가합니다.

Answer Quality: LLM-as-a-Judge 방식으로 자체 평가합니다.

Novelty: ChatHistoryService 대비 신규 정보 기여도를 측정합니다.

DynamicHyperparameterTuner: @Scheduled를 통해 주기적으로(예: 매일 00:00) 통계를 분석하고, HyperparameterService를 통해 Bandit 탐험/활용 비율 및 Reward 가중치 등을 조정합니다.

🛠 클래스별 주요 변경 요약
ChatService.java
DI: HybridRetriever, QueryCorrectionService, CrossEncoderReranker, SmartFallbackService, StrategySelectorService 등 신규 의존성 추가

흐름: 질문 교정 → 전략 선택 → 하이브리드 검색 → 재정렬/Authority → 컨텍스트 통합 → 단일 LLM 호출

프롬프트 정책: 모든 LLM 프롬프트는 PromptBuilder.build(PromptContext)를 통해 생성 (문자열 직접 연결 금지)

안정성: @Slf4j 도입 및 동적 온도 제어, 폴백 태깅 등 안정성 강화

MemoryReinforcementService.java
TM 업데이트와 전략 성적표 StrategyPerformance 동시 강화

@Transactional(noRollbackFor = DataIntegrityViolationException.class) 적용

볼츠만 에너지 계산 및 냉각 스케줄을 인스턴스 메서드로 전환하고, HyperparameterService의 동적 가중치 사용

TranslationMemoryRepository.java
중복 메서드 정리 및 명시적 파라미터 네이밍

업데이트/집계 쿼리를 원자화하여(upsertAndReinforce(...)) DB 왕복 최소화

BanditSelector.java
미선언 파라미터를 @Value 주입으로 정리

decideWithBoltzmann(TranslationMemory tm, double temperature) 시그니처 변경

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
필수 환경변수: OPENAI_API_KEY, PINECONE_API_KEY, PINECONE_ENVIRONMENT, NAVER_API_*

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
# 또는 IDE에서 LmsApplication.java 실행 → http://localhost:8080
🗂️ 프로젝트 구조(요약)
Bash

src/main/java/com/example/lms
├─ api/               # API Controllers (Chat, Feedback, SSE)
├─ config/            # Bean/설정 (WebClientConfig, LangChainConfig, …)
├─ domain/            # JPA 엔티티 (LMS 관련)
├─ dto/               # 요청/응답 DTO (record 적극 활용)
├─ entity/            # JPA 엔티티 (AI/LLM 관련)
├─ repository/        # 데이터 접근
└─ service/
   ├─ rag/            # Retriever/Fuser/Reranker/RAG 서비스
   ├─ correction/     # 질의 교정 서비스
   ├─ reinforcement/  # 강화 학습 및 피드백
   └─ strategy/       # ★ 메타-학습(Selector/Scorer/Tuner)
✅ 운영 원칙
버전 고정: LangChain4j 버전은 1.0.1로 단일 고정하여 API 불일치 문제를 방지합니다.

세션 격리: 각 sessionId별로 DefaultConversationalRetrievalChain을 분리하여 메모리 및 컨텍스트 교차 누수를 금지합니다.

프롬프트 정책: 모든 프롬프트는 PromptBuilder.build(PromptContext)에서 중앙 관리하며, RAG 프롬프트는 "### INSTRUCTIONS: ..."로 시작하고, 근거가 불충분하면 "정보 없음"을 반환합니다.

🤝 기여 가이드
Fork → 브랜치 생성 (feature/*)

커밋 규칙 준수 (feat:, fix:, docs: …)

테스트 포함하여 PR

아키텍처 변경 시 Mermaid 다이어그램 업데이트 필수

📝 커밋 예시
Diff

refactor: 서비스 계층 구조 리팩토링 및 컴파일 오류 수정
- MemoryReinforcementService API 하위호환 Shim 추가
- TranslationMemoryRepository로 DB 쿼리 이관
- ChatService 파이프라인 일원화 및 @Slf4j 도입
Diff

feat: 메타 강화 루프 도입 및 전략 선택 고도화
- StrategySelectorService/ContextualScorer/DynamicHyperparameterTuner 추가
- StrategyPerformance 엔티티 및 레포지토리 도입
- AuthorityScorer 가중 반영 및 2-Pass Meta-Check 명시화
시스템이 스스로 최적의 검색 전략을 학습/평가하는 메타-학습 강화 루프 핵심 기능을 구현.
이 과정에서 발생한 ChatService의 컴파일 오류(연산자 누락)를 수정:
0.5*score 0.5*contextualScore → 0.5 * score + 0.5 * contextualScore

📄 라이선스
MIT License (상세는 LICENSE 참조)
