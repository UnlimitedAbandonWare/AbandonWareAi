📑 하이브리드 RAG AI 챗봇 서비스 (Hybrid RAG AI Chatbot Service)
Spring Boot 기반의 LangChain4j 백엔드에서 1) 실시간 웹 검색, 2) 벡터 DB RAG, 3) 대화형 메모리를 유기적으로 결합하여 신뢰도 높은 답변을 생성합니다.
단순 LLM 호출을 넘어, 다중 정보원을 조합·검증(2-Pass)하여 풍부한 컨텍스트를 제공합니다.

✨ 주요 기능
범주	설명
🚀 하이브리드 정보 검색	• Live Web Search – NaverSearchService<br>• Vector RAG – Pinecone 기반 문서 검색<br>• Conversational Memory – 이전 대화 유지
🔄 동적 파이프라인 라우팅	ChatRequestDto 옵션에 따라<br>① Retrieval ON (Hybrid, 기본)<br>② RAG Stand-Alone<br>③ Retrieval OFF
🪄 2-Pass 답변 정제	1) 초안 생성 → 2) 추가 검색 & FactVerifierService 검증 → 3) 문체 Polish
⚡ 세션 기반 체인 캐싱	Caffeine 으로 세션별 ConversationalRetrievalChain 캐싱
🛡️ 규칙 기반 안전장치	의료·교수 키워드 시 RAG 억제, 공식 도메인 미검증 시 답변 보류 등
🖼️ 아키텍처/데이터 흐름
코드 스니펫

flowchart TD
  A[ChatRequestDto] --> B{Routing<br/>(Hybrid / RAG / Off)}
  subgraph Hybrid
    C1[Web Search] --> D[buildUnifiedContext]
    C2[RAG Search] --> D
    C3[Load Memory] --> D
  end
  B -->|Hybrid| D --> E[Caffeine Cache<br/>get/put Chain] --> F[ConversationalRetrievalChain.execute]
  B -->|RAG Stand-Alone| G[ragsvc.getAnswer] --> F
  B -->|Retrieval OFF| H[Memory-only Context] --> F
  F --> I[LLM Generates Answer] --> J[ChatResult]
TIP: 📂 이미지 파일(docs/architecture.png)을 저장해 위 mermaid 블록 대신 포함할 수 있습니다.

🛠️ 핵심 컴포넌트
Bean	역할
ChatService	요청 분석, 파이프라인 오케스트레이션
HybridRetriever	웹 ↔︎ RAG 결과 통합
NaverSearchService	네이버 검색 API 래퍼
LangChainRAGService	Pinecone 인덱스 질의
FactVerifierService	답변 vs 컨텍스트 사실 검증
Caffeine Cache	세션별 체인 인스턴스 캐싱
⚙️ 설정 (application.yml)
YAML

openai:
  api:
    key: "sk-..."          # OpenAI API 키
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

search:
  official:
    domains: "company-blog.com,official-docs.com"
필수 환경변수
OPENAI_API_KEY (위 openai.api.key와 동일), PINECONE_API_KEY, PINECONE_ENV

📂 Git 저장소 설정 및 최초 업로드 (Project Owner)
이 섹션은 프로젝트 소유자가 저장소를 처음 설정할 때 사용합니다. (협업자는 다음 섹션으로 건너뛰세요.)

로컬 저장소 초기화 및 원격 연결

Bash

# 1. 로컬 폴더에서 Git 시작
git init

# 2. GitHub.com에 생성한 비어있는 저장소와 연결
git remote add origin https://github.com/<your-username>/<your-repo-name>.git
파일 추가, 커밋, 그리고 최초 업로드 (Publish)

Bash

# 3. 모든 파일 추가
git add .

# 4. 첫 커밋 작성
git commit -m "feat: Initial project setup"

# 5. 원격 저장소로 업로드
git push -u origin main
💡 GUI 도구 사용 시
SourceTree나 GitHub Desktop 같은 GUI 툴에서는 'Publish repository' 기능을 사용하여 위 과정을 더 간편하게 처리할 수 있습니다.

🚀 기존 프로젝트 실행 방법 (Collaborators)
Bash

# 1. 저장소 클론
git clone https://github.com/<your-org>/<repo>.git
cd <repo>

# 2. 설정 파일 복사 & 수정
cp src/main/resources/application.yml.example src/main/resources/application.yml
vi src/main/resources/application.yml   # API 키·모델·도메인 등 입력

# 3. 빌드 & 실행 (JDK 17↑)
./gradlew bootRun
# 또는 IDE Run
서비스가 http://localhost:8080/api/chat 에서 기동됩니다.

🧪 예시 요청
POST /api/chat

JSON

{
  "sessionId": "demo-1234",
  "message": "LangChain4j 장점 알려줘",
  "options": {
    "retrieval": "HYBRID"
  }
}
🗂️ 프로젝트 구조 (상위 레벨)
CSS

src
 └─ main
    ├─ java/com/example/chat
    │    ├─ config/
    │    ├─ controller/
    │    ├─ service/
    │    └─ retriever/
    └─ resources/
         └─ application.yml
🤝 Contributing
Fork → Feature branch → PR

커밋 메시지는 feat:, fix:, docs: 규칙 준수
PR 단위로 JUnit 추가 필수

📄 License
Distributed under the MIT License – see LICENSE for details.
