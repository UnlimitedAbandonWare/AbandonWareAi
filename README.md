(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
<a href="https://github.com/anuraghazra/github-readme-stats"><img height="180" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /></a>

Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

단순 LLM 호출을 넘어서 검색/추론으로 스스로 생각하고, 사용자 피드백으로 강화되는 지능형 RAG 시스템입니다. 실시간 웹 검색, 벡터 DB, 책임 연쇄(Chain of Responsibility) 기반 검색 파이프라인, 재랭킹과 2-Pass 검증을 통해 환각(Hallucination)을 최소화합니다. 또한 세션 격리, 캐싱, SSE 스트리밍, 동적 설정 등 운영환경 필수 기능을 제공합니다.

📑 프로젝트 개요
하이브리드 검색: 실시간 웹(Web/Naver), 형태소 분석(Analyze), 벡터 RAG(Pinecone)를 동시·선택적으로 결합해 신뢰도 높은 컨텍스트를 구성

결과 융합/재정렬	다원 소스 **RRF/Softmax** 융합 + Cross-Encoder 재정렬 + Authority 가중	ReciprocalRankFuser, **SoftmaxUtil**, EmbeddingModelCrossEncoderReranker, AuthorityScorer
강화 & 메타-학습: 👍/👎 피드백으로 **전략 성적표(StrategyPerformance)**를 축적, **Softmax(볼츠만 탐색)** 기반으로 다음 질의에서 최적 전략을 **확률적으로 탐색/선택**합니다

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
feat: Softmax 기반 전략 선택 및 융합 기능 도입

기존의 Epsilon-Greedy 및 RRF 방식을 고도화하여, Softmax(볼츠만 탐색)
기반의 지능형 전략 선택 및 결과 융합 메커니즘을 도입합니다.

- StrategySelectorService: Epsilon-Greedy 로직을 Softmax 기반의
  확률적 선택으로 변경하여, 탐험과 활용의 균형을 최적화.
- HybridRetriever: 기존 RRF 방식에 더해, Softmax 기반의 정교한
  점수 융합 및 재정렬(Re-ranking) 모드를 옵션으로 추가.
- 신규 컴포넌트: 수치적으로 안정적인 SoftmaxUtil 및 하이퍼파라미터
  제어를 위한 StrategyHyperparams 추가.
커밋 본문 (Body)
TranslationMemoryRepository 수정

MemoryReinforcementService의 호출로 인한 컴파일 오류를 해결하기 위해 incrementHitCountBySourceHash 메서드를 복원했습니다.

@Modifying 어노테이션을 사용해 hit_count 필드만 정확히 업데이트하도록 하여, 불필요한 DB 쓰기 부하를 줄이고 성능을 개선합니다.
1. 메타 학습 및 동적 전략 선택 아키텍처 도입
StrategySelectorService 및 StrategyPerformance 엔티티를 신설하여, 시스템이 사용자 피드백을 통해 각 검색 전략(예: WEB_FIRST, DEEP_DIVE_SELF_ASK)의 성공률과 평균 보상을 학습하도록 구현했습니다.

이제 시스템은 매 질문마다 과거의 성과 데이터를 기반으로 가장 성공 확률이 높은 검색 전략을 동적으로 선택하여, 스스로 성능을 최적화하는 메타 학습(Meta-Learning) 루프의 기반을 마련했습니다.

피드백을 단순 점수가 아닌 다차원적으로 평가하는 ContextualScorer를 도입하여, 답변의 사실성, 품질, 신규 정보량을 종합적으로 측정하고 이를 강화 학습 보상 점수에 반영했습니다.

2. 2-Pass 검증 및 스마트 폴백을 통한 답변 신뢰도 강화
ChatService 내에 2-Pass 검증 파이프라인을 구축했습니다. 1차로 LLM이 초안(draft)을 생성하면, 2차로 FactVerifierService가 웹 검색 결과와 RAG 컨텍스트를 교차 검증하여 답변의 사실성을 높이고 환각(Hallucination)을 억제합니다.

SmartFallbackService를 도입하여, 컨텍스트가 부족하거나 답변이 "정보 없음"일 경우, 사용자의 의도를 추정하고 대안 키워드를 제시하는 스마트 폴백(Smart Fallback) 기능을 구현했습니다.

답변의 최종 품질을 높이기 위해, 사용자가 선택할 수 있는 2-Pass 폴리싱(Polishing) 옵션을 추가했습니다.

3. 아키텍처 개선 및 코드 리팩토링
ChatService의 역할을 검색, 조합, 생성을 지시하는 오케스트레이터(Orchestrator)로 명확히 분리하여 복잡도를 낮췄습니다.

HybridRetriever, SelfAskWebSearchRetriever 등 검색 관련 컴포넌트를 service.rag 패키지로 모아 모듈성을 강화하고 역할과 책임을 명확히 했습니다.
feat(AI): 메타 학습 강화를 위한 볼츠만 탐색 및 동적 보상 시스템 도입

시스템의 단순 방어 로직을 '지능형 제안 및 학습'으로 진화시키기 위해, 사용자 피드백의 '맥락'을 이해하고 AI의 행동 전략 자체를 강화하는 메타 학습 루프를 도입합니다. 이를 위해 볼츠만 연산 로직을 고도화하고, 답변 출처에 따른 차등 보상 시스템을 적용하며, 위험 질문에 대한 동적 온도 조절 기능을 추가합니다.

1. MemoryReinforcementService & FeedbackController: 차등 보상 시스템 적용

FeedbackDto 수정:

답변의 출처(sourceTag)를 추적하는 필드를 추가합니다. (ASSISTANT, SMART_FALLBACK 등)

applyFeedback 메서드 수정:

sourceTag를 인자로 받아, '스마트 폴백'에 대한 긍정 피드백에는 높은 보상(1.2 이상)을, '환각'으로 의심되는 일반 답변에 대한 부정 피드백에는 강한 페널티(-1.0)를 부여하도록 로직을 수정합니다.

computeBoltzmannEnergy 메서드 수정:

sourceTag가 SMART_FALLBACK인 메모리의 에너지 값에 보너스 점수를 부여하여, 해당 메모리가 선택될 확률을 높입니다.

2. TranslationMemory 엔티티: 답변 출처 추적 기능 추가

필드 추가:

답변의 출처(ASSISTANT, USER_CORRECTION, SMART_FALLBACK 등)를 저장하기 위해 private String sourceTag; 컬럼을 추가합니다.

3. ChatService: 동적 제어 및 전략 오케스트레이션 강화

'스마트 폴백' 답변 태깅:

SmartFallbackService 호출 결과가 실제 폴백 답변일 경우, sourceTag를 "SMART_FALLBACK"으로 지정하여 메모리 강화 로직에 전달합니다.

동적 온도 조절 (Dynamic Temperature) 도입:

FallbackHeuristics.detect()를 사용하여 환각 위험이 높은 질문(예: "원신 + 에스코피에")을 감지합니다.

위험 감지 시, 해당 요청에 한해 LLM의 temperature를 매우 낮은 값(예: 0.05)으로 동적으로 오버라이드하여 답변의 안정성을 확보합니다.

reinforceAssistantAnswer 메서드 수정:

sourceTag를 파라미터로 받도록 시그니처를 변경하고, MemoryReinforcementService 호출 시 태그를 그대로 전달합니다.

4. SmartFallbackService: 폴백 답변 식별 기능 추가

신규 DTO/Record 생성:

FallbackResult(String suggestion, boolean isFallback)와 같은 반환 타입을 새로 정의합니다.

maybeSuggest 메서드 수정:

반환 타입을 기존 String에서 FallbackResult로 변경하여, ChatService가 폴백 답변 여부를 명확히 식별할 수 있도록 합니다.

5. BanditSelector: 외부 제어를 위한 리팩토링

decideWithBoltzmann 메서드 수정:

decideWithBoltzmann(TranslationMemory tm, double temperature)와 같이 온도를 외부에서 파라미터로 주입받도록 시그니처를 변경합니다.

이를 통해 ChatService에서 동적으로 조절된 온도를 직접 전달할 수 있는 구조를 마련합니다.
DynamicChatModelFactory를 도입하여 런타임에 모델, 온도(temperature) 등의 파라미터를 동적으로 설정할 수 있도록 유연성을 확보했습니다.
RestTemplateConfig 개선

Spring Boot 3.x에서 deprecated 된 setConnectTimeout, setReadTimeout 메서드를 최신 API(connectTimeout, readTimeout)로 교체했습니다.

향후 API 제거로 인한 문제를 예방하고 빌드 경고를 제거합니다.
📄 라이선스
MIT License (LICENSE 참조)
