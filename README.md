<a href="https://github.com/anuraghazra/github-readme-stats">
  <img height=200 align="center" src="https://github-readme-stats.vercel.app/api?username=UnlimitedAbandonWare" />
</a>

(AbandonWare) 하이브리드 RAG AI 챗봇 서비스
프로젝트의 핵심 기능, 아키텍처, 설정 방법, 그리고 개발 과정에서의 기술적 회고를 포함하여 명확하고 구조적으로 정리했습니다.

📑 프로젝트 개요 (Hybrid RAG AI Chatbot Service)
Spring Boot와 LangChain4j를 기반으로 1) 실시간 웹 검색, 2) 벡터 DB RAG, 3) 대화형 메모리를 유기적으로 결합하여 신뢰도 높은 답변을 생성하는 AI 챗봇 백엔드입니다. 단순 LLM 호출을 넘어, 다중 정보원을 조합하고 검증(2-Pass)하여 환각을 최소화하고 풍부한 컨텍스트를 제공하는 것을 목표로 합니다.

✨ 주요 개선 사항
이 프로젝트는 다음과 같은 핵심적인 문제들을 해결하며 발전했습니다.

HybridRetriever & SimpleReranker 신규 도입: 여러 검색기(Web, Analyze, RAG)의 결과를 취합하고, 간단한 토큰 기반 재순위화를 통해 관련성 높은 스니펫을 우선적으로 올리는 중앙 오케스트레이터를 구현했습니다.

ChatService 파이프라인 재설계: HybridRetriever를 통해 **단 한 번의 통합 검색(1-Pass)**을 수행하고, 그 결과를 LLM 호출(invoke... 메서드)에 직접 전달하여 중복 검색과 불필요한 LLM 호출을 제거했습니다.

세션 격리 및 공용 데이터 처리 강화:

ChatService에서 Query 객체에 세션 ID(META_SID)를 메타데이터로 주입하여 모든 하위 검색기에 일관되게 전파합니다.

LangChainRAGService는 세션 ID가 일치하거나, 없거나, 공용(*)인 문서를 모두 포함하도록 필터링 로직을 수정했습니다.

EmbeddingStoreManager는 DB에서 로드하는 모든 임베딩에 공용(sid="*") 메타데이터를 부여하여 모든 세션에서 접근 가능하게 했습니다.

검색 유연성 확보 (WebSearchRetriever): 불필요한 오탐을 유발할 수 있는 하드코딩된 도메인 필터를 제거하여 더 많은 잠재적 정답 후보를 확보하도록 수정했습니다.

🚀 주요 기능
기능 범주	설명
하이브리드 정보 검색	NaverSearchService를 통한 실시간 웹 검색, Pinecone 기반의 벡터 RAG, 그리고 대화의 맥락을 유지하는 메모리를 동적으로 조합합니다.
동적 파이프라인 라우팅	ChatRequestDto 옵션에 따라 (1) Retrieval ON (Hybrid, 기본), (2) RAG Stand-Alone, (3) Retrieval OFF 모드로 자동 전환됩니다.
2-Pass 답변 정제	1) 초안 생성 → 2) FactVerifierService를 통한 추가 검색 및 사실 검증 → 3) 최종 문체 Polish 과정을 거쳐 답변의 신뢰도를 높입니다.
세션 기반 체인 캐싱	Caffeine을 사용하여 세션별 ConversationalRetrievalChain을 캐싱함으로써 반복 요청에 대한 응답 속도를 향상시킵니다.
고성능 비동기 통신	Netty를 도입하여 WebSocket 통신을 처리합니다. Netty는 고성능 프로토콜 서버/클라이언트를 신속하게 개발하기 위한 비동기 이벤트 기반 프레임워크입니다.
규칙 기반 안전장치	의료·교수 등 민감 키워드 감지 시 RAG를 억제하거나, 공식 도메인이 검증되지 않았을 때 답변을 보류하는 등의 안전장치를 포함합니다.
💡 개발 회고 및 기술 분석 (Developer's Notes)
총평
이 프로젝트는 Spring Boot 생태계에 대한 깊은 이해를 바탕으로, 복잡한 AI 기반 웹 애플리케이션을 설계하고 구축하는 과정을 담고 있습니다. 최신 AI 기술 트렌드를 학습하고 이를 실제 프로젝트에 통합하는 데 중점을 두었습니다.

기술적 강점 (Strengths)
탄탄한 Spring Boot 이해도:

DI, AOP, JPA, Security 등 Spring의 핵심 기능을 활용하여 계층형 아키텍처(Controller-Service-Repository)를 명확하게 구축했습니다. (ChatApiController, ChatService, UserRepository 등)

@Configuration, @Value, @Component 등을 활용한 설정 관리 및 빈(Bean) 등록을 체계적으로 관리합니다. (WebClientConfig, LangChainConfig)

@Transactional(readOnly=true) 등 세부 속성을 적절히 사용하여 데이터베이스 성능을 고려했습니다. (UserService)

AI 기술 통합 능력 (LangChain4j & RAG):

LangChain4j를 활용하여 하이브리드 검색 파이프라인을 직접 설계하고 구현했습니다. 이는 단순 API 호출을 넘어 AI 모델의 동작 방식을 깊이 이해하려는 시도입니다. (HybridRetriever, SelfAskWebSearchRetriever)

RAG 개념을 바탕으로 Vector DB(Pinecone), 웹 검색, 내부 메모리를 결합하여 LLM의 환각을 줄이고자 했습니다. (LangChainRAGService, EmbeddingStoreManager)

재순위화(Reranking), 결과 융합(Fusion)과 같은 고급 RAG 기술을 직접 구현하여 검색 결과의 정확도를 높였습니다. (CrossEncoderReranker, ReciprocalRankFuser)

비동기 및 동시성 처리 능력:

@Async, CompletableFuture, WebFlux(Mono, Flux)를 도입하여 현대적인 비동기 프로그래밍 모델을 적용했습니다. (TrainingService, ChatApiController)

Semaphore, ConcurrentHashMap 등을 사용하여 동시성 이슈를 예방하고 시스템 안정성을 높였습니다. (NaverSearchService)

Netty 도입 배경: WebSocket 통신을 위해 Netty를 직접 설정했습니다. 이전의 Mina Core 사용 시 간헐적으로 패킷이 반복 전송되는 버그가 있었는데, 이를 해결하기 위해 Netty 소켓으로 전환했습니다. 흥미롭게도 Mina와 Netty는 동일한 개발자에 의해 만들어졌으며, Netty는 Mina의 후속작으로 더 개선된 아키텍처를 제공하여 비동기 소켓 통신에서 뛰어난 성능과 안정성을 보여주었습니다.

객체 지향 설계 및 클린 코드:

ChatService를 Thin Orchestrator로 설계하고, 복잡한 로직은 FactVerifierService, HybridRetriever 등 단일 책임을 갖는 헬퍼 클래스로 분리하여 유지보수성을 높였습니다.

record 타입을 DTO로 활용하고, Optional, Stream API 등 모던 Java 문법을 적극적으로 사용하여 코드를 간결하고 안전하게 작성했습니다.

PromptEngine, CrossEncoderReranker 등 인터페이스를 기반으로 유연하고 확장 가능한 설계를 지향합니다.

향후 개선 영역 (Future Work)
예외 처리 구체화: 현재 catch (Exception e) 블록을 사용하는 부분을 비즈니스 요구사항에 맞는 구체적인 커스텀 예외로 전환하고, @ControllerAdvice를 활용해 전역적으로 처리하여 견고성을 높일 계획입니다.

테스트 코드 확충: JUnit, Mockito 등을 활용한 단위/통합 테스트 커버리지를 높여 코드의 신뢰성을 확보하고, 리팩토링에 대한 안정성을 강화할 예정입니다.

설정 관리 일관성 확보: @Value와 @ConfigurationProperties 사용을 일관성 있게 정리하고, 관련된 설정들은 별도의 *Properties 클래스로 묶어 타입-세이프(type-safe)한 관리를 지향합니다.

🖼️ 아키텍처 및 데이터 흐름
코드 스니펫

graph TD
    A[ChatRequestDto] --> B{라우팅};
    B -->|Retrieval ON (Hybrid)| C(HybridRetriever);
    B -->|RAG Stand-Alone| G[ragSvc.getAnswer];
    B -->|Retrieval OFF| H[memSvc.loadContext];

    subgraph HybridRetriever
        C1[WebSearchRetriever] --> C;
        C2[AnalyzeWebSearchRetriever] --> C;
        C3[LangChainRAGService] --> C;
    end

    C --> D[buildUnifiedContext];
    H --> D;
    G --> J[ChatResult];

    subgraph ChatService
        D --> I{invokeLangChain / invokeOpenAiJava};
    end

    I --> J;
🛠️ 핵심 컴포넌트
Bean	역할
ChatService	요청 분석, 파이프라인 오케스트레이션 수행.
HybridRetriever	웹, 분석, RAG 검색 결과를 통합하고 재순위화.
NaverSearchService	네이버 검색 API 호출을 위한 래퍼.
LangChainRAGService	Pinecone 벡터 DB 인덱스를 질의.
FactVerifierService	생성된 답변과 원본 컨텍스트 간의 사실 검증 수행.
Caffeine Cache	세션별 대화 체인 인스턴스를 캐싱.
⚙️ 설정 (application.yml)
프로젝트를 실행하려면 src/main/resources/application.yml 파일을 다음과 같은 구조로 설정해야 합니다.

YAML

openai:
  api:
    key: "sk-..."                  # OpenAI API 키
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
    name: "my-knowledge-base"      # Pinecone 인덱스 이름

search:
  official:
    domains: "company-blog.com,official-docs.com" # 신뢰하는 공식 도메인 목록
필수 환경변수:

OPENAI_API_KEY: OpenAI API 키

PINECONE_API_KEY: Pinecone API 키

PINECONE_ENVIRONMENT: Pinecone 프로젝트 환경 (예: gcp-starter)

🚀 시작하기
1. 프로젝트 소유자: Git 저장소 초기 설정
Bash

# 1. 로컬에서 Git 저장소 시작
git init

# 2. GitHub에 생성한 원격 저장소와 연결
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPOSITORY>.git

# 3. 모든 파일 추가
git add .

# 4. 첫 커밋 작성
git commit -m "feat: Initial project setup"

# 5. 원격 저장소로 업로드 (main 브랜치)
git push -u origin main
2. 협업자: 기존 프로젝트 실행
Bash

# 1. 저장소 복제 (Clone)
git clone https://github.com/<OWNER_USERNAME>/<REPOSITORY_NAME>.git
cd <REPOSITORY_NAME>

# 2. 설정 파일 복사 및 수정
cp src/main/resources/application.yml.example src/main/resources/application.yml
vi src/main/resources/application.yml

# 3. 빌드 및 실행 (JDK 17 이상 필요)
./gradlew bootRun
IDE(IntelliJ, Eclipse 등)에서 Application.java를 직접 실행해도 됩니다. 서비스는 http://localhost:8080에서 시작됩니다.

🧪 예시 요청
POST /api/chat

JSON

{
    "sessionId": "demo-1234",
    "message": "LangChain4j의 장점은 무엇인가요?",
    "useWebSearch": true,
    "useRag": true
}
🗂️ 프로젝트 구조
src
└── main
    ├── java
    │   └── com/example/lms
    │       ├── config/         # Bean 설정
    │       ├── controller/     # API 엔드포인트
    │       ├── dto/            # 데이터 전송 객체
    │       ├── entity/         # JPA 엔티티
    │       ├── repository/     # 데이터 접근
    │       └── service/        # 비즈니스 로직
    │           └── rag/        # RAG 관련 서비스 및 Retriever
    └── resources
        ├── application.yml
        └── application.yml.example
🤝 기여하기
이 저장소를 Fork 하세요.

새로운 기능 브랜치를 생성하세요 (git checkout -b feature/AmazingFeature).

변경사항을 커밋하세요 (git commit -m 'feat: Add some AmazingFeature'). (커밋 메시지는 feat:, fix:, docs: 등의 규칙을 준수해주세요.)

브랜치에 Push 하세요 (git push origin feature/AmazingFeature).

Pull Request를 열어주세요. (PR 단위로 충분한 JUnit 테스트 코드를 추가해야 합니다.)

📄 라이선스
이 프로젝트는 MIT 라이선스에 따라 배포됩니다. 자세한 내용은 LICENSE 파일을 참조하십시오.

📌 아키텍처 다이어그램
코드 스니펫

flowchart TD
    subgraph Client[클라이언트 계층]
        A1[LMS 웹/모바일 UI]
        A2[카카오톡 알림/메시지]
        A3[번역 요청, AI 질문, 학습 관리]
    end

    subgraph Controller[컨트롤러 계층]
        B1[AdaptiveTranslateController]
        B2[TranslationController]
        B3[TrainingController]
        B4[기타 LMS 기능 컨트롤러]
    end

    subgraph Service[서비스 계층]
        subgraph AI[AI 호출]
            C1[GPTService]
            C2[PromptService]
            C3[PromptBuilder / SystemPrompt / PromptContext]
        end

        subgraph Translation[번역]
            C4[TranslationService]
            C5[AdaptiveTranslationService]
        end

        subgraph Training[학습/튜닝]
            C6[TrainingService]
            C7[FineTuningService]
            C8[WeightTuningService]
        end

        subgraph RLHF[RLHF(강화학습)]
            C9[MemoryReinforcementService]
            C10[ReinforcementQueue]
        end

        subgraph Quality[품질 검증]
            C11[FactVerifierService]
            C12[QualityMetricService]
        end

        subgraph RAG[검색/RAG]
            C13[NaverSearchService]
            C14[EmbeddingStoreManager]
            C15[RagConfig (미완)]
            C16[RagRetrievalService (미완)]
            C17[LangChainChatService (미완)]
        end
    end

    subgraph Data[데이터 & 외부 서비스 계층]
        D1[LMS DB (사용자, 과제, 메모리)]
        D2[벡터 DB(예정)]
        D3[OpenAI/HuggingFace API]
        D4[Naver API]
        D5[Kakao API]
    end

    %% 연결 관계
    Client --> Controller
    Controller --> Service
    AI --> Translation
    AI --> Training
    AI --> RLHF
    AI --> Quality
    AI --> RAG
    Translation --> Data
    Training --> Data
    RLHF --> Training
    Quality --> RAG
    RAG --> Data
    Service --> Data
