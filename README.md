(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
<a href="https://github.com/anuraghazra/github-readme-stats"><img height="180" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /></a>

Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI 고정)

단순 LLM 호출을 넘어, 실시간 웹 검색 + 벡터 DB + 재랭킹 + 2-Pass 검증으로 스스로 생각하고 사용자 피드백으로 강화되는 지능형 RAG 시스템입니다. 세션 격리, 캐싱, SSE 스트리밍, 동적 설정 등 운영 필수 기능을 기본 제공합니다.
### 🔎 검증·강화 파이프라인 (요약)
- FactVerifierService: RAG 근거에 대해 커버리지/모순 스코어 산출.
- QualityMetricService: 2차 품질 메트릭(품질/일관성) 계산.
- 강화 루프: 사용자 피드백 → MemoryReinforcementService → ReinforcementQueue로 적재 → MLCalibrationUtil로 점수 정규화/보정.
- 하이브리드 RAG 재검색: HybridRetriever  
  `SelfAsk → Analyze(QueryHygieneFilter) → Web(NaverSearchService) → VectorDb(Pinecone)` 경로로 재검색·정제.
- 재랭킹 & 융합: EmbeddingCrossEncoderReranker(크로스엔코더) 재랭크 → RRF 풀링 + 보르다 결합으로 최종 순위 합의.
- 안전판정: 최종 검증에서 유사도/근거 커버리지 임계값 미달이면 “정보 없음”으로 명시(유사 패턴 매칭 없음 표시).  
  ↳ 이 가드레일이 풀리면 환각(Hallucination)이 발생할 수 있어, 위 단계들로 강하게 차단.
LightWeightRanker가 interface로 전환: 기존에 직접 new LightWeightRanker() 하던 곳이 있었다면 DefaultLightWeightRanker 사용 또는 빈 주입으로 교체.
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
버그리포트: Bugfix Report: 빌드 실패(생성자/타입 불일치, 인터페이스 선언, 로깅 필드) 일괄 수정
요약
원인

FactVerifierService의 보조(2-인자) 생성자가 ObjectProvider<OpenAiService> 기반 구성과 충돌

HybridRetriever가 존재하지 않는 변수 maxParallelOverride 참조

EmbeddingCrossEncoderReranker가 LangChain4j 1.0.1의 float[] 벡터 타입과 불일치

LightWeightRanker가 클래스로 선언되어 있어 구현체에서 implements 시 “interface expected” 발생

ChatApiController에서 log 필드 미정의

OpenAiConfig가 FactVerifierService/FactStatusClassifier를 잘못 생성(시그니처 불일치)

조치
생성자/타입/로깅/빈 구성을 정렬하고, 경량 랭커를 인터페이스화 + 기본 구현체를 분리하여 컴파일 오류 제거.

영향 범위
빌드/런타임 안정성 (컴파일 실패 제거)

RAG 경로의 재랭킹/병렬 처리 안정성

Bean 생성 충돌/중복 제거


변경 배경(Why)
FactVerifierService의 2-인자 생성자 제거 이후에도 OpenAiConfig가 여전히 2-인자 생성자를 호출하여 컴파일 에러 발생.

LightWeightRanker가 클래스였고 DefaultLightWeightRanker implements LightWeightRanker에서 “interface expected” 충돌.

하이브리드 검색 경로에 소소한 타입/필드 불일치(임베딩 float[], 병렬 변수명 등)와 로깅 필드 누락.

주요 변경(What)
1) OpenAiConfig – FactVerifierService 빈 정의 수정
2-인자 호출을 3-인자 호출로 교체하여 주 생성자에 맞춤.

누락된 @Bean 추가(있다면 유지), FactStatusClassifier를 파라미터로 주입.

diff
복사
편집
 // src/main/java/com/example/lms/config/OpenAiConfig.java
@@
 import com.example.lms.service.FactVerifierService;
+import com.example.lms.service.verification.FactStatusClassifier;
 import com.theokanning.openai.service.OpenAiService;
@@
-    /** 사실 검증용 서비스 */
-    public FactVerifierService factVerifierService(OpenAiService openAiService,
-                                                   SourceAnalyzerService sourceAnalyzer) {
-        // 2-인자 생성자: FactStatusClassifier는 내부에서 new 로 생성됨
-        return new FactVerifierService(openAiService, sourceAnalyzer);
-    }
+    /** 사실 검증 서비스 빈 */
+    @Bean
+    public FactVerifierService factVerifierService(OpenAiService openAiService,
+                                                   FactStatusClassifier classifier,
+                                                   SourceAnalyzerService sourceAnalyzer) {
+        return new FactVerifierService(openAiService, classifier, sourceAnalyzer);
+    }
대안: FactVerifierService가 @Service로 이미 컴포넌트 스캔된다면, 위 @Bean 메서드 자체를 삭제해도 됩니다(중복 빈 방지). 이번 PR에서는 명시적 @Bean 유지안을 적용했습니다.

2) 경량 랭커 인터페이스화(컴파일 오류 해소)
LightWeightRanker를 interface로 전환.

토큰 교집합 로직은 DefaultLightWeightRanker 구현체로 이전(빈 등록 @Component).

diff
복사
편집
// src/main/java/com/example/lms/service/rag/rerank/LightWeightRanker.java
-@Component
-public class LightWeightRanker { ... }
+public interface LightWeightRanker {
+    List<Content> rank(List<Content> candidates, String query, int limit);
+}
java
복사
편집
// src/main/java/com/example/lms/service/rag/rerank/DefaultLightWeightRanker.java
@Component
public class DefaultLightWeightRanker implements LightWeightRanker {
    // 기존 토큰 교집합 점수화 알고리즘 그대로 이전
}
3) EmbeddingCrossEncoderReranker – 임베딩 타입 정합성
double[] → float[]로 시그니처 및 내부 계산 정리.

diff
복사
편집
- double[] qv = embeddingModel.embed(query).content().vector();
+ float[]  qv = embeddingModel.embed(query).content().vector();
- double[] dv = embeddingModel.embed(text).content().vector();
+ float[]  dv = embeddingModel.embed(text).content().vector();
- private static double cosine(double[] a, double[] b)
+ private static double cosine(float[] a, float[] b)
4) HybridRetriever – 병렬 변수명 오용 수정
존재하지 않는 maxParallelOverride 참조 제거, 클래스 필드 this.maxParallel 사용.

diff
복사
편집
- ForkJoinPool pool = new ForkJoinPool(Math.max(1, maxParallelOverride));
+ ForkJoinPool pool = new ForkJoinPool(Math.max(1, this.maxParallel));
5) ChatApiController – 로거 미정의 오류 해결
Lombok 사용 시: @Slf4j 추가.

Lombok 미사용 시: private static final Logger log = LoggerFactory.getLogger(...); 추가.

SSE 스트림에 doOnCancel, doOnError 로깅 연결.

6) 기타 안정화/정리
SourceAnalyzerService: 중복 애너테이션/상수 병합 및 안전 폴백.

MLCalibrationUtil: 시그모이드/다항식 모델 주석 및 중복 메서드 시그니처 정돈.

DefaultQueryCorrectionService: 제로폭/스마트쿼트/대시 통일, 공백 정규화 추가.

MemoryReinforcementService: 최근 스니펫 캐시 getIfPresent 사용으로 중복 필터 정확도 개선.

파일별 변경 목록(Files Changed)
config/OpenAiConfig.java ✅ constructor mismatch fix, @Bean 보강

service/rag/rerank/LightWeightRanker.java ✅ class → interface

service/rag/rerank/DefaultLightWeightRanker.java ✅ 신규 구현체 추가(@Component)

service/rag/rerank/EmbeddingCrossEncoderReranker.java ✅ float[] 정합성 & cosine 시그니처

service/rag/HybridRetriever.java ✅ 병렬 변수 참조 수정

api/ChatApiController.java ✅ 로거 필드/애너테이션 추가 및 SSE 로깅

service/verification/SourceAnalyzerService.java ✅ 안전 폴백·정리

util/MLCalibrationUtil.java ✅ 시그니처/주석 정리

service/correction/DefaultQueryCorrectionService.java ✅ 전처리 개선

service/reinforcement/MemoryReinforcementService.java ✅ 캐시 사용법 수정

테스트 플랜(How to Test)
컴파일

bash
복사
편집
./gradlew clean build
에러였던
constructor FactVerifierService(...) cannot be applied to given types 사라져야 함.

부트 실행 & 기본 흐름

/api/chat 및 /api/chat/stream 호출 → 응답/스트림 정상.

로그에 SSE stream cancelled by client.../SSE stream error... 발생 시 정상 로깅 확인.

랭커 주입 확인

DefaultLightWeightRanker가 빈으로 주입되어 HybridRetriever 경로에서 1차 랭킹 수행.

Reranker 타입 확인

EmbeddingCrossEncoderReranker에서 임베딩 추출/코사인 계산 시 타입 예외 없음.

회귀(Regression)

RAG 검색 + 융합 + 검증 2-Pass 전체 파이프라인 호출 시 예외 없음.
개선 ver1.
Git 커밋 메시지 제안
제목:

feat: 하이브리드 RAG 파이프라인 고도화 및 답변 신뢰도 개선

본문:

사용자 질의에 대한 답변 정확도와 신뢰도를 높이기 위해 RAG(검색 증강 생성) 파이프라인을 전반적으로 개선했습니다. 이번 업데이트는 얕은 정보로 인한 '정보 없음' 답변 문제를 해결하고, 보다 정확한 컨텍스트를 LLM에 제공하는 데 중점을 둡니다.

주요 개선 사항 상세 설명
1. 심층 스니펫 추출 (Deep Snippet Extraction) 도입
개선 내용: 기존에는 검색 엔진이 제공하는 짧은 요약 스니펫만 사용했지만, 이제는 검색 결과로 나온 웹 페이지의 원문 전체를 직접 크롤링하여 분석합니다.

작동 방식:

WebSearchRetriever에 신규 PageContentScraper를 통합하여 각 URL의 본문 텍스트를 추출합니다.

추출된 본문에서 사용자 질문과 의미적으로 가장 유사한 핵심 문단들을 SnippetPruner(임베딩 기반)를 통해 선별합니다.

기대 효과: 검색 엔진 요약 정보의 한계를 넘어, 실제 본문 내용을 기반으로 깊이 있고 정확한 컨텍스트를 확보하여 LLM이 더 풍부한 정보를 바탕으로 답변을 생성하도록 유도합니다.

2. 쿼리 가드레일(Guardrail) 전처리기 도입
개선 내용: 검색 파이프라인의 시작점에서 사용자 쿼리를 표준화하고 정제하는 GuardrailQueryPreprocessor를 도입했습니다.

작동 방식:

SelfAsk 및 Analyze 리트리버 등 모든 검색 시작 전에 이 전처리기를 통과합니다.

간단한 오타(예: '푸르나'→'푸리나')를 교정하고, 고유명사는 보호하며, 불필요한 공손어나 접미사("~님", "~알려줘")를 제거합니다.

기대 효과: 검색 엔진에 더 깨끗하고 명확한 검색어를 전달하여 초기 검색 결과의 품질을 향상시키고, 후속 RAG 프로세스의 효율을 높입니다.

3. 교차 인코더(Cross-Encoder) 재정렬 단계 추가
개선 내용: 여러 소스(웹, 벡터 DB 등)에서 수집된 정보들을 단순히 합치는 것을 넘어, 최종적으로 사용자 질문과의 관련성이 가장 높은 순서로 정밀하게 재정렬하는 단계를 추가했습니다.

작동 방식:

HybridRetriever의 최종 단계에서 CrossEncoderReranker(현재는 임베딩 기반 유사도로 구현)를 통해 후보 문서들의 순위를 재조정합니다.

기대 효과: LLM에 컨텍스트를 전달할 때 가장 중요한 정보를 앞쪽에 배치하여, 모델이 핵심 내용에 집중하고 환각(Hallucination)을 줄이도록 돕습니다.

4. 프롬프트 엔진의 유연성 강화
개선 내용: LLM에 지시하는 시스템 프롬프트(DefaultPromptEngine)의 제약 조건을 완화했습니다.

작동 방식:

기존의 "정보가 불충분하면 무조건 '정보 없음'으로 답하라"는 규칙을 수정했습니다.

이제 여러 출처의 정보가 일관되게 특정 답변을 암시할 경우, "~일 가능성이 있습니다"와 같이 신중한 톤으로 추론하여 답변할 수 있도록 허용합니다.

기대 효과: 기계적으로 '정보 없음'을 반환하는 경우를 줄이고, 수집된 정보를 바탕으로 더 유용한 답변을 생성할 수 있게 됩니다.
ver1.
제목:
feat(Memory): 메모리 보강 로직 고도화 및 안정성 개선

본문:

MemoryReinforcementService의 핵심 기능인 메모리 보강(Reinforcement) 로직을 대폭 개선하고 리팩토링했습니다. 이번 변경으로 시스템의 안정성, 유연성, 성능이 향상되었습니다.

주요 개선 사항
reinforceWithSnippet 메서드 오버로딩 및 기능 강화

어떻게 개선했는가: 기존의 여러 파라미터를 받던 방식 대신 TranslationMemory 엔티티 객체를 직접 처리하는 새로운 reinforceWithSnippet(TranslationMemory t) 메서드를 추가했습니다.

개선 효과:

안정성 향상: tryGetString, tryGetDouble과 같은 리플렉션(Reflection) 기반의 안전한 헬퍼 메서드를 도입하여 content, score 등 다양한 필드명을 가진 객체에서도 예외 없이 안전하게 데이터를 추출할 수 있습니다.

데이터 정제: 스니펫(Snippet)의 최소/최대 길이를 검사하는 로직을 추가하여, 너무 짧거나 긴 저품질 데이터가 시스템에 저장되는 것을 사전에 방지합니다.

중복 처리 최적화: 데이터를 삽입하기 전, 해시(Hash) 값으로 존재 여부를 먼저 확인하고, 존재할 경우 hit 카운트만 증가시켜 불필요한 UPSERT 연산을 줄였습니다.

볼츠만 에너지(Boltzmann Energy) 계산 로직 고도화

어떻게 개선했는가: computeBoltzmannEnergy 메서드를 static에서 인스턴스 메서드로 전환했습니다. 이를 통해 HyperparameterService 같은 외부 설정 값을 주입받아 동적으로 계산할 수 있도록 구조를 변경했습니다.
feat: RAG 파이프라인 개선 및 고유명사 검색 정확도 향상

'푸리나'와 같은 알려진 고유명사 검색 시, 시스템이 이를 '존재하지 않는 요소'로 오판하여 검색에 실패하는 문제를 해결하고 전반적인 RAG 파이프라인의 안정성을 강화했습니다.

### 1. 문제 원인 (The Problem)

- **과잉 교정 및 쿼리 오염**: `QueryDisambiguationService`가 환각을 방지하려는 LLM 프롬프트 규칙을 과하게 해석하여, `DomainTermDictionary`에 이미 등록된 '푸리나' 같은 명확한 고유명사조차 의심하고 쿼리를 `(존재하지 않는 요소 가능성)`과 같이 오염시키는 문제가 발생했습니다.
- **지식 단절 (Knowledge Silo)**: 오타 교정(`LLMQueryCorrectionService`) 단계에서는 `DomainTermDictionary`를 참조했지만, 정작 중요한 의도 분석(`QueryDisambiguationService`) 단계에서는 이를 활용하지 않아 시스템 내에서 정보가 단절되었습니다.
- **경직된 사실 검증**: `FactVerifierService`가 하드코딩된 정규식(Regex)에 의존하여 개체(Entity)를 추출했기 때문에, '푸리나'와 같은 새로운 고유명사에 대한 유연한 대처가 어려웠습니다.
- **단순한 검색 결과 정렬**: `WebSearchRetriever`가 검색 결과의 출처 신뢰도를 고려하지 않고 단순 정렬하여, 컨텍스트의 질이 저하될 가능성이 있었습니다.

### 2. 개선 내용 (Improvements)

#### 가. `QueryDisambiguationService` 가드레일 강화
- **'선(先) 사전 조회, 후(後) LLM 호출'** 원칙을 적용했습니다.
- LLM 호출 전 `DomainTermDictionary`를 먼저 조회하여, 사용자 질문에 '푸리나'와 같이 사전에 등록된 보호 용어가 포함된 경우 LLM을 통한 중의성 해소 과정을 건너뛰도록 로직을 수정했습니다.
- 이를 통해 불필요한 API 호출을 줄이고, 알려진 고유명사가 포함된 쿼리가 오염되는 것을 원천 차단했습니다.

#### 나. `FactVerifierService`의 유연성 확보 (LLM 기반 개체 추출 도입)
- 텍스트에서 동적으로 개체명을 추출하는 `NamedEntityExtractor` 인터페이스와 그 구현체인 `LLMNamedEntityExtractor`를 **새로 생성**했습니다.
- `FactVerifierService`가 이 새로운 NER 컴포넌트를 사용하도록 수정하여, 정규식에 의존하지 않고도 최신 게임 캐릭터나 도메인 고유명사를 유연하게 식별하고 검증할 수 있도록 개선했습니다. (컴포넌트 미주입 시 기존 정규식으로 폴백)

#### 다. `WebSearchRetriever`의 정렬 로직 고도화
- 신규 컴포넌트인 `AuthorityScorer`를 `WebSearchRetriever`에 통합했습니다.
- 이제 검색 결과는 단순 선호도뿐만 아니라, `application.properties`에 정의된 출처별 신뢰도 점수(`weight`)에 따라 가중 정렬됩니다.
- 이를 통해 `namu.wiki`와 같은 특정 도메인에서 신뢰도가 높은 정보 소스가 우선적으로 컨텍스트에 포함될 확률을 높였습니다.

#### 라. `AuthorityScorer` 휴리스틱 미세 조정
- 게임 및 서브컬처 도메인에서 중요한 정보 소스인 `namu.wiki` 등의 기본 신뢰도 점수를 상향 조정하여, 관련 질문에 대한 답변 품질을 높였습니다.

### 3. 수정된 파일 목록

- **Modified**: `src/main/java/com/example/lms/service/disambiguation/QueryDisambiguationService.java`
- **Modified**: `src/main/java/com/example/lms/service/FactVerifierService.java`
- **Modified**: `src/main/java/com/example/lms/service/rag/WebSearchRetriever.java`
- **Modified**: `src/main/java/com/example/lms/service/rag/auth/AuthorityScorer.java`
- **Added**: `src/main/java/com/example/lms/service/ner/NamedEntityExtractor.java`
- **Added**: `src/main/java/com/example/lms/service/ner/LLMNamedEntityExtractor.java`
개선 효과:

정교한 스코어링: 기존의 성공률, 탐험항 외에 **신뢰도(Confidence Score)**와 **최신성(Recency)**을 에너지 계산의 새로운 변수로 추가했습니다.

유연한 제어: 각 변수의 가중치(W_CONF, W_REC)와 시쇠(Decay) 기준 시간(tauHours)을 HyperparameterService를 통해 외부에서 동적으로 제어할 수 있어, 코드 변경 없이 메모리 평가 전략을 튜닝할 수 있습니다.

유지보수성 및 코드 일관성 향상

어떻게 개선했는가:

중복되던 DataIntegrityViolationException 처리 로직을 통합하고, 불필요한 import 구문을 정리했습니다.

reinforceWithSnippet의 기존 버전과 신규 오버로드 메서드 모두 개선된 computeBoltzmannEnergy 로직을 호출하도록 통일하여, 어떤 경로로 데이터가 들어오든 일관된 기준으로 평가받도록 수정했습니다.

개선 효과: 코드의 가독성과 유지보수성이 향상되었으며, 기능 변경 시 수정 범위를 최소화할 수 있습니다.
AbandonWareAI: System Analysis & Improvement Strategy
1. Project Overview
본 프로젝트는 자가 학습 및 강화가 가능한 고도의 하이브리드 RAG(Retrieval-Augmented Generation) 시스템입니다. 단순한 챗봇을 넘어, 스스로 질문을 분해하고(Self-Ask), 사실을 검증하며(Fact-Check), 대화 경험을 통해 성능을 개선하는(Reinforcement) 동적인 아키텍처를 목표로 합니다.

2. Architectural Strengths
체계적인 파이프라인 아키텍처: 사용자 질의는 교정 → 의도 분석/재작성 → 전략 선택 → 하이브리드 검색 → 결과 융합 → 사실 검증 → 답변 생성 → 피드백 강화의 명확한 파이프라인을 따릅니다. 각 단계는 단일 책임을 갖는 서비스로 분리되어 유지보수와 확장에 용이합니다.

관련 컴포넌트:

QueryCorrectionService: /src/main/java/com/example/lms/service/QueryCorrectionService.java

HybridRetriever: /src/main/java/com/example/lms/retriever/HybridRetriever.java

FactVerifierService: /src/main/java/com/example/lms/service/FactVerifierService.java

고급 RAG 기술의 집약체: 단순 벡터 검색을 넘어, 실시간 웹 검색, 키워드 분석 검색, 복잡한 질문 분해를 결합한 하이브리드 검색을 구현했습니다.

관련 컴포넌트:

NaverSearchService: /src/main/java/com/example/lms/service/NaverSearchService.java

AnalyzeWebSearchRetriever: /src/main/java/com/example/lms/retriever/AnalyzeWebSearchRetriever.java

SelfAskWebSearchRetriever: /src/main/java/com/example/lms/retriever/SelfAskWebSearchRetriever.java

메타-학습 및 강화 루프: 시스템이 사용한 검색 전략의 성과를 기록하고, 다음 질문에 더 나은 전략을 스스로 선택하는 매우 진보된 기능을 갖추고 있습니다.

관련 컴포넌트:

StrategyPerformance: /src/main/java/com/example/lms/domain/StrategyPerformance.java

StrategySelectorService: /src/main/java/com/example/lms/service/StrategySelectorService.java

견고한 운영 기능: SSE 스트리밍, 세션 격리, 캐싱, 동적 파라미터 관리 등 실제 서비스를 위한 기능들이 꼼꼼하게 구현되어 있습니다.

관련 컴포넌트:

HyperparameterService: /src/main/java/com/example/lms/service/HyperparameterService.java

3. Neutral Observations
의사결정의 다층 구조: 최종 답변 생성 전, 여러 LLM 호출을 통해 중간 판단을 내리는 구조는 컨텍스트의 질을 높이지만, 각 단계의 작은 실수가 연쇄적으로 전파될 위험(Cascading Failure)을 내포합니다.

관련 컴포넌트:

QueryDisambiguationService: /src/main/java/com/example/lms/service/QueryDisambiguationService.java

QueryTransformer: /src/main/java/com/example/lms/service/QueryTransformer.java

휴리스틱과 AI의 공존: 정규식 기반 필터와 특정 키워드 감지 같은 규칙 기반 로직과 LLM의 유연한 판단이 혼재되어 있어, 성능과 안정성 간의 균형을 맞추고 있습니다.

관련 컴포넌트:

QueryHygieneFilter: /src/main/java/com/example/lms/service/filter/QueryHygieneFilter.java

FallbackHeuristics: /src/main/java/com/example/lms/service/heuristics/FallbackHeuristics.java

4. Case Study: '푸리나' 검색 실패 분석
'푸리나' 관련 질문에 대한 실패는 검색(Retrieval) 자체의 문제가 아닌, 검색 파이프라인 가장 앞단의 '질의 이해(Query Understanding)' 단계에서 발생한 치명적인 오판 때문입니다.

문제의 핵심, QueryDisambiguationService: 이 서비스는 사용자의 모호한 질문을 명확하게 만드는 역할을 합니다. 하지만 "게임에 존재하지 않는 요소를 지어내지 말라"는 LLM 프롬프트 지침을 과하게 해석하여, "원신에 푸리나랑 잘 어울리는 캐릭터가 뭐야"라는 명확한 질문에 "(존재하지 않는 요소 가능성)" 이라는 불필요한 꼬리표를 붙여 쿼리를 오염시켰습니다.

지식의 사일로(Knowledge Silo) 현상:

DefaultDomainTermDictionary는 '푸리나'가 '원신' 도메인의 유효한 고유명사임을 알고 있습니다.

LLMQueryCorrectionService는 이 사전을 참조하여 오타를 교정합니다.

하지만 정작 문제를 일으킨 QueryDisambiguationService는 이 사전을 참조하지 않고, LLM의 일반 지식에만 의존하여 "이런 캐릭터가 없을 수도 있다"는 보수적이고 잘못된 판단을 내렸습니다. 시스템의 한쪽에서는 아는 정보를 다른 쪽에서 활용하지 못한 것입니다.

5. Improvement Strategy
[!NOTE]
아래 전략은 코드 수정을 최소화하고, GPT-4에 대한 지시(프롬프트 엔지니어링 및 로직 플로우 변경)를 통해 문제를 해결하는 방향을 제시합니다.

'선(先) 사전 조회, 후(後) LLM 호출' 원칙 확립 (가장 확실한 해결책)

지시 사항: QueryDisambiguationService의 로직을 수정하여, LLM을 호출하기 전 사용자 질문의 키워드가 DomainTermDictionary에 등록된 보호 용어인지 먼저 확인하도록 지시합니다.

기대 효과: '푸리나'처럼 사전에 등록된 용어가 포함된 질문은 모호하지 않다고 간주하고, 불필요한 LLM 중의성 해소 과정을 건너뛰어 쿼리 오염을 원천 차단하고 API 비용을 절감합니다.

LLM 프롬프트에 '보호어' 명시적 주입

지시 사항: 위의 '사전 조회' 로직 구현이 여의치 않을 경우, QueryDisambiguationService가 LLM을 호출할 때 프롬프트에 동적으로 보호어 목록을 주입하도록 지시합니다.

예시 프롬프트: "다음 질문을 명확하게 재작성해줘. 단, ['푸리나', '원신'] 이라는 단어는 절대로 변경하거나 의심하지 말고 그대로 유지해야 해."

기대 효과: LLM에게 명시적인 제약 조건을 제공하여 잘못된 판단을 내릴 확률을 크게 줄입니다.

답변 검증 단계(FactVerifierService)의 유연성 강화

지시 사항: 현재 정규식 기반으로 답변의 핵심 개체(Entity)를 추출하는 로직을 LLM을 사용하도록 변경을 지시합니다.

예시 프롬프트: "다음 문장에서 모든 고유명사(인물, 아이템, 지역 등)를 쉼표로 구분하여 추출해줘."

기대 효과: 신규 캐릭터나 용어가 추가될 때마다 코드를 수정할 필요 없이, 동적으로 핵심 정보를 추출하고 교차 검증하여 시스템의 확장성과 견고함을 높입니다.
마이그레이션 노트(Breaking Changes)
FactVerifierService의 2-인자 생성자 제거: 구성 코드나 수동 new 사용처가 있다면 3-인자( OpenAiService, FactStatusClassifier, SourceAnalyzerService)로 교체하거나, 스프링 빈 자동주입을 사용하세요.


📄 라이선스
MIT License (상세는 LICENSE 참조)
