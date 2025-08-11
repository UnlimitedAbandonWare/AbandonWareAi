(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
<a href="https://github.com/anuraghazra/github-readme-stats"><img height="180" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /></a>

Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

단순 LLM 호출을 넘어서 검색/추론으로 스스로 생각하고, 사용자 피드백으로 강화되는 지능형 RAG 시스템입니다. 실시간 웹 검색, 벡터 DB, 책임 연쇄(Chain of Responsibility) 기반 검색 파이프라인, 재랭킹과 2-Pass 검증을 통해 환각(Hallucination)을 최소화합니다. 또한 세션 격리, 캐싱, SSE 스트리밍, 동적 설정 등 운영환경 필수 기능을 제공합니다.

📑 프로젝트 개요
하이브리드 검색: 실시간 웹(Web/Naver), 형태소 분석(Analyze), 벡터 RAG(Pinecone)를 동시·선택적으로 결합해 신뢰도 높은 컨텍스트를 구성

결과 융합/재정렬: 병렬 수집 → RRF 융합 → Cross-Encoder 재정렬 → Authority(출처 신뢰도) 가중

검증 기반 생성: 초안 → Meta-Check & Fact-Check(2-Pass) → 사실성 보장

실시간 UX: SSE(Server-Sent Events)로 단계별 진행 상황 스트리밍

강화 & 메타-학습: 👍/👎 피드백은 장기 기억(TranslationMemory)뿐 아니라 **전략 성적표(StrategyPerformance)**로 축적되어, 다음 질의에서 최적 전략을 스스로 선택합니다

✨ 주요 기능 및 컴포넌트
범주	설명	핵심 컴포넌트
질의 향상	오타/구어체 교정, 의도 기반 키워드 동적 확장	LLMQueryCorrectionService, QueryAugmentationService, QueryComplexityGate
하이브리드 검색	Naver(웹), Analyze(형태소), Pinecone(벡터 RAG)	HybridRetriever, NaverSearchService, AnalyzeWebSearchRetriever
결과 융합/재정렬	다원 소스 RRF 융합 + Cross-Encoder 재정렬 + Authority 가중	ReciprocalRankFuser, EmbeddingModelCrossEncoderReranker, AuthorityScorer
2-Pass 검증	Meta-Check(주제 일치) → Fact-Check(근거 교차검증)	FactVerifierService
실시간 스트리밍	처리 과정을 SSE로 실시간 전송 (“생각하는 기능”)	ChatApiController(/stream), chat.js
강화 학습	피드백을 보상 점수로 반영 (“강화 기능”)	FeedbackController, MemoryReinforcementService
세션 관리	META_SID 기반 전 파이프라인 세션 격리 + 캐싱	ChatHistoryService, PersistentChatMemory, Caffeine
고성능 통신	Netty/WebFlux 비동기	NettyServerConfig
메타 강화 학습	전략을 스스로 학습/선택, 시스템 파라미터 자동 튜닝	StrategySelectorService, ContextualScorer, DynamicHyperparameterTuner, StrategyPerformance(엔티티)

🧠 아키텍처 & 흐름
1) 검색·생성 파이프라인 (안전장치/신뢰도 반영)
mermaid
코드 복사
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

    HR --> RNK[RRF + (Simple/Cross) Rerank]
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
2) 메타-학습 & 전략 선택 루프（자가 진화）
mermaid
코드 복사
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
환각 긴급 대응
– 쿼리 재작성·사실 검증·RAG 프롬프트 강화로 잘못된 추론 차단

핵심 기능 구현
– SSE 스트리밍(생각하는 기능), 사용자 피드백(강화 기능) 운영 안정화

컴파일 오류 해결 & 구조 개선(리팩토링)
– MemoryReinforcementService API 정리(Shim 유지), DB 쿼리 TranslationMemoryRepository로 이관, 누락된 log 변수/스코프/중복 선언 정리

UX 고도화(스마트 폴백)
– “정보 없음” 대신 의도 추정과 대안 제시

메타 강화 루프 도입(시스템 자가 진화)

전략적 행동 선택: StrategySelectorService가 과거 성과 기반으로 가장 성공률 높은 검색 전략을 동적으로 선택

다차원 성과 측정: ContextualScorer가 사실성/품질/정보가치를 종합 평가해 보상(Reward)을 고도화

자동 파라미터 튜닝: DynamicHyperparameterTuner가 주기적으로 탐험/활용 비율, 가중치 등 하이퍼파라미터를 조정

🧩 도메인 & 서비스 (신규 포함)
신규 엔티티: StrategyPerformance
strategyName (e.g., WEB_FIRST, VECTOR_FIRST, DEEP_DIVE_SELF_ASK, WEB_VECTOR_FUSION)

queryCategory (예: “제품 스펙 문의”, “단순 사실 질문”)

successCount, failureCount

averageReward (피드백 기반 평균 점수)

용도: FeedbackController → MemoryReinforcementService 경유로 전략 성적 집계/업데이트

신규 서비스
StrategySelectorService: 질의 특징(길이, 키워드, 의도 등) + StrategyPerformance 통계 기반 최적 전략 선택, ChatService가 HybridRetriever 호출 전 질의

ContextualScorer:

Factuality – FactVerifierService로 RAG 근거 충실도

Answer Quality – LLM-as-a-Judge 자체 평가

Novelty – ChatHistoryService 대비 신규 정보 기여도

DynamicHyperparameterTuner: @Scheduled(예: 매일 00:00)로 TM/StrategyPerformance 통계 분석 → HyperparameterService 통해 Bandit 탐험/활용, Reward 가중치 등 조정

🛠 클래스별 주요 변경 요약
ChatService.java

DI: HybridRetriever, QueryCorrectionService, CrossEncoderReranker, SmartFallbackService, StrategySelectorService(신규)

흐름: 질문 교정 → 전략 선택 → 하이브리드 검색 → 재정렬/Authority → 컨텍스트 통합 → 단일 LLM 호출

프롬프트 정책: 모든 LLM 프롬프트는 PromptBuilder.build(PromptContext) 경유(문자열 직접 연결 금지)

안정성: @Slf4j 도입

MemoryReinforcementService.java

TM 업데이트 + 전략 성적표 StrategyPerformance 동시 강화

트랜잭션: @Transactional(noRollbackFor = DataIntegrityViolationException.class)

TranslationMemoryRepository.java

중복 메서드 제거(incrementHitCountBySourceHash(...)), 명시적 파라미터 네이밍, 업데이트/집계 쿼리 정리

BanditSelector.java

미선언 파라미터를 @Value 주입으로 정리(Teng, Tsoft, betaCos 등)

⚙️ 설정 예시 (application.yml)
yaml
코드 복사
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
bash
코드 복사
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
bash
코드 복사
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
버전 고정 원칙: 런타임/API 오류 시 가장 먼저 LangChain4j 혼합 버전(0.2.x vs 1.0.x) 존재 여부 점검. 프로젝트는 1.0.1 단일 라인에 고정

세션 격리: 각 sessionId별 DefaultConversationalRetrievalChain 분리, 교차 누수 금지

프롬프트 정책: 모든 프롬프트는 **PromptBuilder.build(PromptContext)**에서 생성(중앙화된 null-check/테스트/포맷 관리)

🤝 기여 가이드
Fork → 브랜치 생성(feature/*) → 커밋 규칙(feat:, fix:, docs: …) → 테스트 포함 PR

아키텍처 변경 시 Mermaid 다이어그램 업데이트 필수

커밋 예시

diff
코드 복사
refactor: 서비스 계층 구조 리팩토링 및 컴파일 오류 수정
- MemoryReinforcementService API 하위호환 Shim 추가
- TranslationMemoryRepository로 DB 쿼리 이관
- ChatService 파이프라인 일원화 및 @Slf4j 도입
- 중복/오타/스코프 오류 정리

feat: 메타 강화 루프 도입 및 전략 선택 고도화
- StrategySelectorService/ContextualScorer/DynamicHyperparameterTuner 추가
- StrategyPerformance 엔티티 및 레포지토리 도입
- AuthorityScorer 가중 반영 및 2-Pass Meta-Check 명시화
시스템이 스스로 최적의 검색 전략을 학습하고 평가하는 '메타-학습 강화 루프'의 핵심 기능을 구현합니다. 이 과정에서 발생한 ChatService의 컴파일 오류를 수정합니다.

### 주요 변경 내역

* **신규 서비스 및 엔티티 추가 (메타-학습)**
    * `StrategySelectorService`: 과거 성과 기반으로 최적 검색 전략(WEB_FIRST, VECTOR_FIRST 등)을 동적으로 선택합니다.
    * `ContextualScorer`: 답변의 사실성, 품질, 신규성을 다차원적으로 평가하여 보상 점수를 고도화합니다.
    * `StrategyPerformance`: 각 전략의 성과(성공/실패, 평균 보상)를 기록하는 엔티티 및 Repository를 추가합니다.
    * `DynamicHyperparameterTuner`: `@Scheduled`를 통해 매일 시스템의 학습 파라미터를 자동 튜닝합니다.

* **기존 서비스 연동 및 수정**
    * `ChatService`: 검색 전 `StrategySelectorService`를 호출하여 전략을 결정하고, 답변 생성 후 `ContextualScorer`의 평가 점수를 `reinforceAssistantAnswer` 메서드에 반영합니다.
    * `MemoryReinforcementService`: 사용자 피드백 수신 시, `TranslationMemory` 강화와 더불어 `StrategyPerformance` 성적표를 함께 업데이트합니다.

* **버그 수정**
    * `ChatService`: `reinforceAssistantAnswer` 메서드 내 강화 점수 계산식에서 누락되었던 `+` 연산자를 추가하여 컴파일 오류를 해결합니다.
      (수정 전: `0.5*score 0.5*contextualScore`)
      (수정 후: `0.5 * score + 0.5 * contextualScore`)

* **README 업데이트**
    * 새로운 아키텍처 다이어그램과 메타-학습 루프에 대한 설명을 반영하여 프로젝트 문서를 최신화합니다.
📄 라이선스
MIT License (LICENSE 참조)
