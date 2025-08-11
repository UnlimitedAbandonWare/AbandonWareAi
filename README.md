(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
<a href="https://github.com/anuraghazra/github-readme-stats"> <img height="180" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /> </a>
Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

단순 LLM 호출을 넘어서 검색/추론으로 스스로 생각하고, 사용자 피드백으로 강화되는 지능형 RAG(검색 증강 생성) 시스템입니다. 실시간 웹 검색, 벡터 DB, 책임 연쇄(Chain of Responsibility) 기반 검색 파이프라인, 재랭킹과 2-Pass 검증을 통해 환각(Hallucination)을 최소화합니다. 또한 세션 격리, 캐싱, SSE 스트리밍, 동적 설정 등 운영환경 필수 기능을 제공합니다.

📑 프로젝트 개요
실시간 웹 검색(Web), 형태소 분석 기반 검색(Analyze), 벡터 RAG(Vector/Pinecone)를 결합해 신뢰도 높은 컨텍스트를 동적으로 구성합니다. 파이프라인은 다음 단계를 거칩니다.

질의 향상 (Query Augmentation & Correction)
사용자 질문을 정제·확장해 검색 품질 극대화.

심층 검색 & 정제 (Deep Search & Refinement)
복수 소스 병렬 수집 → RRF 융합 → Cross-Encoder 재정렬.

검증 기반 생성 (Grounded Generation)
초안 → 근거 교차검증(2-Pass) → 사실성 보장.

실시간 스트리밍 UX (“생각하는 기능”)
SSE(Server-Sent Events)로 단계별 진행 상황 실시간 표시.

강화 기능 (Reinforcement Learning)
👍/👎 피드백을 장기 기억(Translation Memory)에 반영해 점진적 성능 개선.

✨ 주요 기능 및 컴포넌트
범주	설명	핵심 컴포넌트
질의 향상	오타/구어체 교정, 의도 기반 키워드 동적 확장	LLMQueryCorrectionService, QueryAugmentationService, QueryComplexityGate
하이브리드 검색	Naver(웹), Lucene(형태소), Pinecone(벡터 RAG) 동시 활용	HybridRetriever, NaverSearchService, AnalyzeWebSearchRetriever
결과 융합/재정렬	다원 소스 RRF 융합 + Cross-Encoder 재정렬	ReciprocalRankFuser, EmbeddingModelCrossEncoderReranker
2-Pass 검증	초안 생성 후 검색 근거와 교차 검증	FactVerifierService
실시간 스트리밍	처리 과정을 SSE로 실시간 전송 (“생각하는 기능”)	ChatApiController(/stream), chat.js
강화 학습	피드백을 보상 점수로 반영 (“강화 기능”)	FeedbackController, MemoryReinforcementService
세션 관리	META_SID로 전 파이프라인 세션 격리 + Caffeine 캐싱	ChatHistoryService, PersistentChatMemory
고성능 통신	Netty(WebSocket), WebFlux(@Async/CompletableFuture)	NettyServerConfig, AdaptiveTranslationService

버전 고정 원칙: 런타임/API 오류 발생 시 우선 LangChain4j 혼합 버전(0.2.x vs 1.0.x) 유무를 점검합니다. 프로젝트는 LangChain4j 1.0.1 단일 라인에 고정되어야 합니다.

🧠 아키텍처 & 흐름
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

    HR --> RNK[Rerank (Simple / Cross) + RRF]
    RNK --> CTX[buildUnifiedContext]
    MC --> CTX
    RG --> OUT1[ChatResult]

    subgraph ChatService
      CTX --> LLM{LLM Call}
    end

    LLM --> D1[Draft Answer]
    D1 --> FV[FactVerifierService (2-Pass Verification)]
    FV --> OUT2[Final Answer]
🚀 개발 과정 & 주요 변경 내역
1) 환각 긴급 대응
‘에스코피에’ 사례를 계기로 쿼리 재작성·사실 검증·RAG 프롬프트 강화로 잘못된 추론을 차단.

2) 핵심 기능 구현
SSE 스트리밍(생각하는 기능), 사용자 피드백(강화 기능) 구현 및 운영 안정화.

3) 컴파일 오류 해결 & 구조 개선 (리팩토링)
대규모 변경 후 발생한 16+ 컴파일 오류를 해결.

API 불일치: MemoryReinforcementService 변경에 맞춰 호출부 정렬, Shim 메서드(reinforceWithSnippet, loadContext) 통해 하위호환 확보.

역할 분리: Service에 있던 DB 쿼리를 **TranslationMemoryRepository**로 이관.

문법/선언 오류: 중복 선언, 누락된 log 변수, 스코프 오류 정리.

4) UX 고도화 (스마트 폴백)
“정보 없음” 대신 의도 추정과 대안 제시.
예) “에스코피에는 원신 캐릭터가 아닙니다. 다른 캐릭터를 찾으시나요?”

🛠 클래스별 상세 변경
ChatService.java (코어 파이프라인 개편)
DI 추가: HybridRetriever, QueryCorrectionService, CrossEncoderReranker, SmartFallbackService 등.

continueChat 흐름 통합: 질문 교정 → 하이브리드 검색 → 재정렬 → 통합 컨텍스트 → 단일 LLM 호출.

프롬프트 엔지니어링: WEB_PREFIX, RAG_PREFIX, MEM_PREFIX로 출처 우선순위 명시.

모든 LLM 프롬프트는 PromptBuilder.build(PromptContext) 경유 (문자열 직접 연결 금지).

안정성: @Slf4j 도입으로 누락된 log 변수 오류 해결.

MemoryReinforcementService.java (안정성/호환성)
DB 쿼리 전면 이관 → TranslationMemoryRepository.

예외 시 롤백 과민 반응 완화: @Transactional(noRollbackFor=DataIntegrityViolationException.class) 적용.

Shim 메서드 유지로 대규모 변경 후 호출부 수정 최소화.

TranslationMemoryRepository.java (DB 계층)
중복 메서드 제거: incrementHitCountBySourceHash(...) 중복 선언 제거로 프록시 생성 문제 해소.

업데이트·집계 쿼리 메서드 정리 및 명시적 파라미터 네이밍.

TranslationMemory.java (엔티티)
중복 필드 제거, 누락된 괄호·생성자 보강(new TranslationMemory(String)).

BanditSelector.java (ML 선택 로직)
미선언 파라미터를 @Value 주입으로 정리(Teng, Tsoft, betaCos 등).

Frontend & Controllers
chat.js: Enter 전송 / Shift+Enter 줄바꿈, 오류 시 로딩 아이콘 스톱 현상 수정.

FeedbackController, ChatApiController: API 호출/스트리밍 안정화.

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
🗂️ 프로젝트 구조 (요약)
bash
코드 복사
src/main/java/com/example/lms
├─ api/              # API Controllers (Chat, Feedback, SSE)
├─ config/           # Bean/설정 (WebClientConfig, LangChainConfig, …)
├─ domain/           # JPA 엔티티 (LMS 관련)
├─ dto/              # 요청/응답 DTO (record 적극 활용)
├─ entity/           # JPA 엔티티 (AI/LLM 관련)
├─ repository/       # 데이터 접근
└─ service/
   ├─ rag/            # Retriever/Fuser/Reranker/RAG 서비스
   ├─ correction/     # 질의 교정 서비스
   ├─ reinforcement/  # 강화 학습 및 피드백 관련 서비스
   └─ ...
🤝 기여 가이드
Fork → 브랜치 생성(feature/*) → 커밋 규칙(feat:, fix:, docs: …) 준수 → 테스트 포함 PR.

아키텍처 변경 시 Mermaid 다이어그램 업데이트 부탁드립니다.

Git 커밋 메시지 예시

diff
코드 복사
refactor: 서비스 계층 구조 리팩토링 및 컴파일 오류 수정

- MemoryReinforcementService API 하위호환 Shim 추가
- TranslationMemoryRepository로 DB 쿼리 이관
- ChatService 파이프라인 일원화 및 @Slf4j 도입
- 중복/오타/스코프 오류 정리
feat: AI 환각 방지를 위한 다층적 방어 전략 도입

- 신뢰도 기반 검색(Source Authority) 시스템을 추가하여 검증되지 않은 웹 정보의 영향을 줄임.
- LLM 프롬프트를 수정하여 비판적 사고를 유도하고, 맹신적 답변 생성을 억제함.
- FactVerifier, SmartFallback 등 기존 안전장치를 고도화하여 컨텍스트-질문 간 불일치를 탐지하고 더 나은 사용자 경험을 제공하도록 개선.
README.md 파일 업데이트 전략
README.md 파일을 다음 세 부분으로 나누어 업데이트하는 것을 제안합니다.

1. 📑 프로젝트 개요 및 ✨ 주요 기능 및 컴포넌트 섹션 보강
기존 설명에 환각 방지와 정보 신뢰도에 대한 내용을 명시적으로 추가하여 프로젝트의 차별점을 부각합니다.

[프로젝트 개요] 문단에 다음 내용을 추가하거나 수정합니다.

"...재랭킹과 2-Pass 검증을 통해 환각(Hallucination)을 최소화합니다. 특히, 출처 신뢰도 평가(Source Authority) 시스템을 도입하여 공식 정보와 팬 창작물을 구분하고, 잘못된 정보를 AI가 맹신하는 문제를 구조적으로 방지합니다."

[주요 기능 및 컴포넌트] 표에 다음 항목을 수정하거나 추가합니다.

결과 융합/재정렬: 설명에 "출처 신뢰도 점수 반영" 추가

2-Pass 검증: 설명에 "답변 생성 전 컨텍스트-질문 주제 일치 여부 메타 체크(Meta-Check) 기능 추가" 강조

강화 학습: 설명에 "스마트 폴백 답변에 대한 긍정 피드백 가중치 부여" 추가

2. 🧠 아키텍처 & 흐름 섹션 고도화
Mermaid 다이어그램을 수정하여 새로운 안전장치와 신뢰도 평가 단계를 명확히 보여줍니다.

[아키텍처 & 흐름] Mermaid 차트를 아래와 같이 수정합니다.

HybridRetriever 다음에 AuthorityScorer를 활용하는 Rank by Authority 단계를 추가합니다.

FactVerifierService의 설명을 **Meta-Check & Fact-Check**로 변경하여 사전 검증 단계를 명시합니다.

Final Answer가 나오기 전에 SmartFallback? 분기점을 추가하여 UX 고도화 흐름을 보여줍니다.

코드 스니펫

flowchart TD
    U[User Request] --> R{Mode Routing}

    R -->|Retrieval ON| HR(HybridRetriever)
    subgraph HybridRetriever
      W[Web Search] --> HR
      A[Analyze] --> HR
      V[Vector RAG] --> HR
    end

    HR --> AUTH[Rank by Authority Score]
    AUTH --> CTX[Build Unified Context]

    subgraph ChatService
      CTX --> LLM{LLM Call}
    end

    LLM --> D1[Draft Answer]
    D1 --> FV[Meta-Check & Fact-Check]

    FV --> FB{Smart Fallback?}
    FB -- N --> FINAL[Final Answer]
    FB -- Y --> SUGGEST[Suggest Alternatives]

    R -->|Retrieval OFF| RG[LangChainRAGService]
    RG --> FINAL
3. 🚀 개발 과정 & 주요 변경 내역 섹션 업데이트
환각 대응을 위한 구체적인 개선 사항을 명시하여 프로젝트의 발전 과정을 보여줍니다.

[개발 과정 & 주요 변경 내역] 목록에 다음 항목을 추가합니다.

5) 환각 방지 시스템 고도화 (Source Authority & Meta-Check)

출처 신뢰도 평가: AuthorityScorer를 도입, 검색 결과의 출처(공식, 위키, 커뮤니티 등)에 따라 가중치를 차등 부여하여 랭킹 정확도 향상.

비판적 프롬프팅: LLM이 웹 검색 결과를 맹신하지 않고, 출처 신뢰도에 따라 비판적으로 정보를 종합하도록 시스템 프롬프트 수정.

컨텍스트 사전 검증: FactVerifierService에 질문과 컨텍스트의 주제 일치 여부를 먼저 확인하는 '메타 체크' 로직을 추가하여 관련 없는 정보 기반의 답변 생성을 원천 차단.

지능형 실패 처리: SmartFallbackService가 발동된 답변을 "SMART_FALLBACK"으로 태깅, MemoryReinforcementService가 해당 답변에 긍정 피드백을 받을 시 더 높은 보상 점수를 부여하여 AI의 자가 교정 학습을 유도.

이와 같이 문서를 부분적으로 수정하면, Git 커밋 내역과 프로젝트 문서가 일관성을 유지하며 AI 시스템의 핵심 개선 사항을 명확하게 전달할 수 있습니다.
📄 라이선스
MIT License (LICENSE 참조)
