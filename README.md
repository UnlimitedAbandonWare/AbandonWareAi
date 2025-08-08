
프로젝트의 핵심 기능, 아키텍처, 설정 방법, 그리고 최근 개선 사항까지 모두 포함하여 명확하고 구조적으로 정리했습니다.

📑 하이브리드 RAG AI 챗봇 서비스 (Hybrid RAG AI Chatbot Service)
Spring Boot와 LangChain4j를 기반으로 1) 실시간 웹 검색, 2) 벡터 DB RAG, 3) 대화형 메모리를 유기적으로 결합하여 신뢰도 높은 답변을 생성하는 AI 챗봇 백엔드입니다. 단순 LLM 호출을 넘어, 다중 정보원을 조합하고 검증(2-Pass)하여 환각을 최소화하고 풍부한 컨텍스트를 제공합니다.

✨ 주요 개선 사항
이 프로젝트는 다음과 같은 핵심적인 문제들을 해결하며 발전했습니다.

HybridRetriever & SimpleReranker 신규 도입: 여러 검색기(Web, Analyze, RAG)의 결과를 취합하고, 간단한 토큰 기반 재순위화를 통해 관련성 높은 스니펫을 우선적으로 올리는 중앙 오케스트레이터를 구현했습니다.

ChatService 파이프라인 재설계: HybridRetriever를 통해 **단 한 번의 통합 검색(1-Pass)**을 수행하고, 그 결과를 LLM 호출(invoke... 메서드)에 직접 전달하여 중복 검색과 불필요한 LLM 호출을 제거했습니다.

고유명사 검색 정확도 향상 (AnalyzeWebSearchRetriever): 'DW아카데미'처럼 영문과 한글이 조합된 고유명사는 형태소 분석을 건너뛰고 원문 그대로 검색하여, 의미 없는 단어로 분해되는 문제를 해결했습니다.

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
규칙 기반 안전장치	의료·교수 등 민감 키워드 감지 시 RAG를 억제하거나, 공식 도메인이 검증되지 않았을 때 답변을 보류하는 등의 안전장치를 포함합니다.
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
팁: 위 다이어그램은 docs/architecture.png와 같은 이미지 파일로 저장하여 시각적으로 더 명확하게 표현할 수 있습니다.

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
이 섹션은 프로젝트를 처음 생성하는 소유자만 해당됩니다.

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
💡 팁: SourceTree나 GitHub Desktop 같은 GUI 툴을 사용하면 'Publish repository' 기능으로 위 과정을 더 간편하게 처리할 수 있습니다.

2. 협업자: 기존 프로젝트 실행
Bash

# 1. 저장소 복제 (Clone)
git clone https://github.com/<OWNER_USERNAME>/<REPOSITORY_NAME>.git
cd <REPOSITORY_NAME>

# 2. 설정 파일 복사 및 수정
# application.yml.example 파일을 복사하여 실제 설정 파일을 생성합니다.
cp src/main/resources/application.yml.example src/main/resources/application.yml

# 복사한 파일을 열어 본인의 API 키 등을 입력합니다.
# vi, nano, VSCode 등 편한 에디터 사용
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

변경사항을 커밋하세요 (git commit -m 'feat: Add some AmazingFeature').

커밋 메시지는 feat:, fix:, docs: 등의 규칙을 준수해주세요.

브랜치에 Push 하세요 (git push origin feature/AmazingFeature).

Pull Request를 열어주세요.

PR 단위로 충분한 JUnit 테스트 코드를 추가해야 합니다.

📄 라이선스
이 프로젝트는 MIT 라이선스에 따라 배포됩니다. 자세한 내용은 LICENSE 파일을 참조하십시오.
