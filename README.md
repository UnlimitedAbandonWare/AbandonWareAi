<a href="https://github.com/anuraghazra/github-readme-stats"> <img height="200" align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" /> </a>
(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
Java 17 · Spring Boot · LangChain4j 1.0.1 (BOM/core/starter/OpenAI)

프로젝트의 핵심 기능/아키텍처/설정/개발 회고를 한눈에 보이도록 정리했습니다. 단순 LLM 호출을 넘어 검색–리랭크–검증이 먼저 도는 하이브리드 RAG 오케스트레이션이 핵심입니다.

📑 프로젝트 개요
Spring Boot와 LangChain4j를 기반으로

실시간 웹 검색, 2) 벡터 DB RAG, 3) 대화형 메모리를 유기적으로 결합해 신뢰도 높은 답변을 생성합니다.
LLM은 교체 가능한 엔진으로 취급하며, 품질의 본체는 웹/분석/벡터 RAG 통합, 재순위화, 세션 격리, 2-Pass 사실 검증에 있습니다.

✨ 주요 개선 사항(하이라이트)
HybridRetriever & SimpleReranker 도입
Web/Analyze/RAG 결과를 취합 → 경량 리랭크 → 컨텍스트 조립하는 중앙 오케스트레이터 구현.

ChatService 파이프라인 재설계
단 한 번의 통합 검색(1-Pass) 결과를 LLM에 바로 전달, 중복 검색/불필요 호출 제거.

세션 격리·공용 데이터 처리 강화

META_SID를 Query 메타데이터로 주입해 전 컴포넌트에 전파.

RAG는 세션 일치 / 무세션 / 공용(*) 문서를 포함하도록 필터링.

EmbeddingStoreManager는 DB 적재 시 공용(sid="*") 메타를 부여.

검색 유연성 확보
WebSearchRetriever의 과도한 도메인 필터 제거 → 정답 후보 폭 확장.

🚀 주요 기능
범주	설명
하이브리드 검색	NaverSearchService(실시간 웹), Pinecone 기반 Vector RAG, 대화 메모리를 동적 조합
모드 라우팅	요청별 (Retrieval ON / RAG only / Retrieval OFF) 자동 전환
2-Pass 정제	LLM 초안 → FactVerifierService 추가검색 검증 → 최종 Polishing
세션 캐싱	Caffeine으로 세션별 ConversationalRetrievalChain 캐싱
고성능 통신	Netty WebSocket + WebFlux(Mono/Flux), @Async/CompletableFuture
규칙 기반 안전장치	민감 키워드/비공식 도메인 시 답변 억제/보류 등 안전 규칙

🧩 아키텍처 한눈에 보기
mermaid
복사
편집
flowchart TD
    U[User Request] --> R{Mode Routing<br/>(Retrieval ON | RAG only | Retrieval OFF)}

    R -->|Retrieval ON| HR(HybridRetriever)
    R -->|RAG only| RAG[LangChainRAGService]
    R -->|Retrieval OFF| MEM[memSvc.loadContext]

    subgraph HybridRetriever
      W[NaverSearchService<br/>Web Search]
      A[AnalyzeWebSearchRetriever]
      V[Vector RAG<br/>(Pinecone)]
      W --> HR
      A --> HR
      V --> HR
    end

    HR --> RNK[Rerank (Simple/Cross)]
    RNK --> CTX[buildUnifiedContext]
    MEM --> CTX
    RAG --> OUT1[ChatResult]

    subgraph ChatService
      CTX --> LLM{LLM Call}
    end

    LLM --> D1[Draft Answer]
    D1 --> FV[FactVerifierService<br/>2-Pass Verification]
    FV --> OUT2[Final Answer]
🔧 핵심 컴포넌트
HybridRetriever: Web/Analyze/Vector RAG 취합→리랭크→컨텍스트 조립

NaverSearchService: 실시간 웹 스니펫 수집

LangChainRAGService: Pinecone 기반 임베딩/검색

Rerankers (Simple/Cross) & ReciprocalRankFuser: 결과 재순위화/융합

buildUnifiedContext: LLM 입력용 컨텍스트 표준화

FactVerifierService: LLM 초안 이후 추가 검색으로 사실 검증(2-Pass)

Session Metadata (META_SID): 세션 격리/전파

Caffeine Cache: 세션·쿼리 레벨 캐싱

🧠 설계 원칙
LLM = 교체 가능한 엔진
LLM은 라우터 뒤에 두고, 검색/리랭크/검증/세션이 가치의 중심.

Chain of Responsibility
SelfAsk → Analyze → Web → VectorDb 핸들러 체인. 실패해도 부분 성과 전파.

Prompt Builder 단일화
ChatService 내 문자열 결합 금지. 모든 프롬프트는
PromptBuilder.build(PromptContext ctx)에서 생성.

세션 격리
세션별 독립 Conversational Retrieval Chain, META_SID로 누출 방지.

🧷 모델/키 라우팅
API 키/모델 교체는 LLM Router에서 처리합니다.
서비스 코드는 라우터로부터 키를 주입받고, 설정만 교체하면 벤더/모델 스위칭.

현재는 **경량 모델(gpt-nano급)**로 테스트 중입니다.
학습 셋/도메인 데이터가 적은 단계에서는 검색·리랭크·검증 체인 튜닝이 더 효과적이며,
데이터가 커지면 라우터 설정만 변경해 상위 모델로 전환합니다.

⚙️ 설정 (예시: application.yml)
yaml
복사
편집
abandonware:
  retrieval:
    mode: RETRIEVAL_ON        # RETRIEVAL_ON | RAG_ONLY | RETRIEVAL_OFF
    reranker: cross           # simple | cross
  session:
    metaKey: META_SID
    cache:
      caffeine:
        spec: maximumSize=1000,expireAfterWrite=5m

llm:
  router:
    provider: OPENAI          # 교체 가능 (OPENAI, ...)
    model: gpt-nano           # 테스트 단계: 경량 모델
    # 키는 환경변수/시크릿 매니저로 주입

naver:
  search:
    enabled: true
    # 키/엔드포인트는 시크릿 주입 (예: NAVER_API_*)

pinecone:
  rag:
    enabled: true
    # apiKey/index/namespace는 시크릿 주입
필수 환경변수(예)
OPENAI_API_KEY, PINECONE_API_KEY, PINECONE_ENVIRONMENT, NAVER_API_*

▶️ 빠른 시작
bash
복사
편집
# 1) 클론 & 이동
git clone https://github.com/<OWNER>/<REPO>.git
cd <REPO>

# 2) 설정 파일 템플릿 복사
cp src/main/resources/application.yml.example src/main/resources/application.yml
vi src/main/resources/application.yml  # 환경값 수정

# 3) 빌드 & 실행 (JDK 17+)
./gradlew bootRun
# 또는 IDE에서 Application.java 실행 → http://localhost:8080
예시 요청

http
복사
편집
POST /api/chat
Content-Type: application/json
json
복사
편집
{
  "sessionId": "demo-1234",
  "message": "LangChain4j의 장점은 무엇인가요?",
  "useWebSearch": true,
  "useRag": true
}
🗂️ 프로젝트 구조(요약)
bash
복사
편집
src/main/java/com/example/lms
├─ config/          # Bean/설정  (WebClientConfig, LangChainConfig, …)
├─ controller/      # API 엔드포인트 (ChatApiController, …)
├─ dto/             # 요청/응답 DTO (record 적극 활용)
├─ entity/          # JPA 엔티티
├─ repository/      # 데이터 접근 레이어
└─ service/
   ├─ rag/          # RAG 서비스·Retriever·Reranker·Fuser
   ├─ quality/      # FactVerifierService, QualityMetricService
   ├─ memory/       # TranslationMemory, MemoryReinforcementService
   └─ ...           # 기타 비즈니스 로직
🧪 트러블슈팅(요약)
LangChain4j 버전 순도: 런타임/API 오류 시 가장 먼저 0.2.x/1.0.x 혼재 여부 확인.

프롬프트 생성 위치: PromptBuilder 우회(문자열 직접 결합) 금지 → 컨텍스트 누락/테스트 불가 방지.

세션 누수: META_SID 누락 시 맥락 혼선 가능.

웹 검색 오탐: 과도한 도메인 필터는 비활성화(상황별 allowlist만 유지 권장).

💡 개발 회고(요약)
Spring Boot 숙련도: DI/AOP/JPA/Security로 계층형 아키텍처를 명확히 유지.

AI 통합 역량: LangChain4j로 하이브리드 검색 → 리랭크 → 컨텍스트 빌드 → 2-Pass 검증을 직접 설계/구현.

비동기/동시성: @Async, CompletableFuture, WebFlux, Netty WebSocket 채택.
(Mina → Netty 전환으로 패킷 반복 전송 이슈 제거, 안정성/성능 향상)

클린 아키텍처: ChatService는 Thin Orchestrator, 복잡 로직은 SRP 컴포넌트로 분리.

향후: 커스텀 예외+@ControllerAdvice, JUnit/Mockito 테스트 커버리지 확장,
@ConfigurationProperties 기반 타입세이프 설정 정리.

🤝 기여 가이드
Fork → 브랜치 생성(feature/*)

커밋 규칙(feat:, fix:, docs: …) 준수

PR 시 충분한 테스트 코드 포함

📄 라이선스
본 프로젝트는 MIT License를 따릅니다. 자세한 내용은 LICENSE를 참조하세요.
