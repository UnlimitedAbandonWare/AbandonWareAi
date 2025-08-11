(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
<a href="https://github.com/anuraghazra/github-readme-stats">
<img height="180" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" />
</a>

Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

단순 LLM 호출을 넘어서 "스스로 생각하고(검색/추론) 사용자 피드백으로 발전하는(강화)" 지능형 RAG(검색 증강 생성) 시스템입니다. 실시간 웹 검색, 벡터 DB, 책임 연쇄(Chain of Responsibility) 패턴 기반의 검색 파이프라인, 재랭킹 및 2-Pass 검증을 통해 환각(Hallucination)을 최소화합니다. 세션 격리, 캐싱, API 스트리밍, 동적 설정 등 운영 환경에 필수적인 기능들을 포함하고 있습니다.

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

환각 긴급 대응: '에스코피에' 환각(AI가 존재하지 않는 것을 사실처럼 말하는 현상)을 막기 위해 쿼리 재작성, 사실 검증, RAG 프롬프트를 강화하여 잘못된 추론을 원천 차단했습니다.

기능 구현 및 리팩토링: SSE 스트리밍('생각하는 기능'), 사용자 피드백('강화 기능')을 구현하면서 발생한 구조적 문제와 문법 오류를 해결했습니다.

API 불일치 해결: 대규모 리팩토링 이후 발생한 16개의 컴파일 오류를 해결했습니다. 이는 여러 서비스가 변경된 MemoryReinforcementService의 예전 메서드를 호출하고 있었기 때문이며, 호환성 유지를 위한 임시 메서드(Shim)를 추가하여 해결했습니다.

UX 고도화 (스마트 폴백): 단순히 "정보 없음"으로 응답하는 대신, 사용자의 의도를 파악하여 대안을 제시하는 지능형 응답 기능을 추가했습니다.

클래스별 상세 변경 내역
ChatService.java (코어 로직 대규모 개편)
의존성(DI) 대거 추가: HybridRetriever, QueryCorrectionService, CrossEncoderReranker, SmartFallbackService 등 전문 서비스를 추가하여 답변 생성 파이프라인을 고도화했습니다.

continueChat 메서드 리팩토링: 질문 교정 → 하이브리드 검색 → 재정렬 → 통합 컨텍스트 생성 → 단일 LLM 호출의 흐름으로 로직을 통합하여 효율성과 확장성을 높였습니다.

프롬프트 엔지니어링 강화: WEB_PREFIX, RAG_PREFIX, MEM_PREFIX 등 정보 출처별 프롬프트를 명시하여 모델이 정보의 우선순위를 더 잘 이해하도록 개선했습니다.

스마트 폴백 도입: FactVerifierService의 결과가 "정보 없음"일 때, SmartFallbackService를 호출하여 "에스코피에는 원신 캐릭터가 아닙니다. 혹시 다른 캐릭터를 찾으시나요?" 와 같이 더 유용한 안내를 제공하도록 개선했습니다.

MemoryReinforcementService.java (안정성 및 호환성 강화)
트랜잭션 롤백 문제 해결: DB 중복 키 예외(DataIntegrityViolationException)가 발생해도 트랜잭션이 롤백되지 않도록 @Transactional(noRollbackFor = ...) 어노테이션을 추가하여 서비스 안정성을 확보했습니다.

호환성 어댑터 추가: reinforceWithSnippet, loadContext 등 예전 API 시그니처를 그대로 갖는 어댑터(Shim) 메서드를 추가하여, 대규모 리팩토링 이후 발생한 "cannot find symbol" 컴파일 오류를 일괄 해결했습니다.

누락된 헬퍼 메서드 추가: normalizeSessionId, storageHashFromSnippet 등 컴파일에 필요했던 내부 유틸리티 메서드들을 추가했습니다.

chat.js & Controllers (프론트엔드 및 API)
UX 개선: Enter 키로 메시지가 전송되고 Shift+Enter로 줄 바꿈이 되도록 자바스크립트 로직을 수정했습니다.

UI 안정화: 서버 통신 오류 시 로딩 아이콘이 사라지지 않고 멈춰 보이는 버그를 수정하고, FeedbackController와 ChatApiController의 API 호출 로직을 안정화했습니다.

신규 클래스 및 지식 강화
FactVerifierService: 답변의 핵심 단어들이 검색 결과에 실제로 존재하는지 확인하는 로직을 추가하여 환각을 억제했습니다.

SmartFallbackService / FallbackHeuristics.java: "정보 없음" 상황에서 사용자의 의도를 교정하고 대안을 제시하는 지능형 응답 로직을 신규 구현했습니다.

DefaultDomainTermDictionary: '다이루크', '에스코피에' 등 고유명사 지식을 추가하여 AI가 단어의 맥락을 더 잘 파악하도록 했습니다.

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
저장소를 Fork → 브랜치 생성(feature/*) → 커밋 규칙(feat:, fix:, docs: …) 준수 → 테스트 포함 PR 생성. 아키텍처 변경 시 Mermaid 다이어그램 업데이트 부탁드립니다.
Git 커밋 메시지
refactor: 서비스 계층 구조 리팩토링 및 컴파일 오류 수정

대규모 리팩토링 이후 발생했던 16개 이상의 심각한 컴파일 오류를 해결하고, 서비스와 리포지토리의 역할을 명확히 분리하여 아키텍처 안정성을 확보합니다.

### 주요 수정 내용

#### 1. API 불일치 및 컴파일 오류 해결 (Hotfix)
- **문제:** `ChatService`가 변경된 `MemoryReinforcementService`의 예전 API를 호출하여 `NoSuchMethodError` 및 `cannot find symbol` 오류 다수 발생.
- **해결:**
    - `MemoryReinforcementService`에 하위 호환성을 위한 **어댑터(Shim) 메서드**(`reinforceWithSnippet`, `loadContext` 등)를 추가하여 API 호출부를 수정하지 않고도 문제를 해결했습니다.
    - 컴파일에 누락되었던 내부 **헬퍼 메서드**(`normalizeSessionId`, `storageHashFromSnippet`, `reward` 등)를 추가했습니다.

#### 2. 서비스-리포지토리 역할 분리 (구조 리팩토링)
- **문제:** 데이터베이스 쿼리 로직(`@Query`, `@Modifying`)이 `MemoryReinforcementService`에 위치하여 Spring Data JPA 원칙에 위배되고 유지보수가 어려운 구조였습니다.
- **해결:**
    - `MemoryReinforcementService`에 있던 모든 **DB 쿼리 정의를 `TranslationMemoryRepository` 인터페이스로 이전**했습니다.
    - 이제 Service는 비즈니스 로직에만 집중하고, 데이터 접근은 Repository가 전담하도록 역할을 명확히 분리했습니다.

#### 3. 기타 문법 및 타입 오류 수정
- **`bad operand types`:** primitive 타입(`double`)을 `null`과 비교하던 오류를 수정했습니다.
- **`<identifier> expected`:** `private` 키워드 오타(`ivate`)를 수정했습니다.
- 코드 병합 과정에서 발생한 중복 `import` 구문과 잘못된 `package` 선언을 정리했습니다.

### 수정한 주요 클래스
- `ChatService.java`: 신규 서비스(Reranker, Fallback 등) DI 추가 및 핵심 로직 파이프라인 개편.
- `MemoryReinforcementService.java`: 어댑터 메서드 추가, 쿼리 로직 제거, 트랜잭션 롤백 방지 설정 추가.
- `TranslationMemoryRepository.java`: 서비스에서 이전된 쿼리 메서드 추가 및 중복 선언 정리.
- `chat.js`: 프론트엔드 UX 버그(Enter 키 전송, 로딩 아이콘 멈춤) 수정.

이번 리팩토링을 통해 빌드 안정성을 확보하고, 각 컴포넌트의 책임과 역할을 명확히 하여 향후 기능 확장 및 유지보수성을 크게 개선했습니다.
📄 라이선스
MIT License (LICENSE 참조)
