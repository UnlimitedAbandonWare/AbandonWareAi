하이브리드 RAG AI 챗봇 서비스 (Hybrid RAG AI Chatbot Service)
이 프로젝트는 Spring Boot 환경에서 LangChain4j를 기반으로, 실시간 웹 검색, 벡터 DB 기반 RAG(Retrieval-Augmented Generation), 그리고 대화형 메모리를 유기적으로 결합한 고성능 AI 챗봇 백엔드 서비스입니다.

단순한 LLM 호출을 넘어, 여러 정보 소스를 동적으로 조합하고 답변을 자체적으로 검증하여 신뢰도 높고 풍부한 컨텍스트를 가진 답변을 생성하는 것을 목표로 합니다.

✨ 주요 기능 (Core Features)
🚀 하이브리드 정보 검색 (Hybrid Retrieval)

Live Web Search: NaverSearchService를 통해 최신 정보를 실시간으로 웹에서 가져옵니다.

Vector RAG: Pinecone 같은 벡터 데이터베이스에 저장된 내부 문서/지식 기반으로 정보를 검색합니다.

Conversational Memory: 사용자와의 이전 대화 기록을 컨텍스트에 포함하여 대화의 연속성을 유지합니다.

🔄 동적 파이프라인 라우팅 (Dynamic Pipeline Routing)

사용자의 요청(ChatRequestDto) 옵션에 따라 최적의 응답 생성 파이프라인을 동적으로 선택합니다.

Retrieval ON (Hybrid): 웹, RAG, 메모리를 모두 활용하는 기본 파이프라인.

RAG Stand-Alone: 벡터 DB의 정보만으로 답변이 필요할 때 사용.

Retrieval OFF: 외부 정보 없이 순수 LLM의 생성 능력과 대화 메모리만 사용.

🔍 2-Pass 답변 정제 (2-Pass Refinement)

초안 생성: 1차 수집된 컨텍스트로 답변의 초안을 생성합니다.

심화 및 검증: 생성된 초안을 힌트 삼아 2차 웹 검색을 수행하고, FactVerifierService를 통해 사실 관계를 검증한 뒤, 자연스러운 문체로 다듬어(Polish) 최종 답변을 완성합니다.

⚡️ 세션 기반 체인 캐싱 (Session-based Chain Caching)

Caffeine 캐시를 활용하여 사용자 세션별 ConversationalRetrievalChain 인스턴스를 캐싱합니다. 이를 통해 동일 세션 내 반복적인 요청에 대해 객체 생성 오버헤드를 줄여 응답 속도를 향상시킵니다.

🛡️ 규칙 기반 안전장치 (Rule-based Safety Gates)

특정 키워드(예: 의료진, 교수) 포함 시 RAG 사용을 억제하여 검증된 웹 정보를 우선합니다.

공식 정보(패치 노트, 공지) 관련 질문 시 지정된 도메인(official.domains)의 검색 결과가 없으면 답변을 보류하여 잘못된 정보 전파를 방지합니다.

⚙️ 아키텍처 및 동작 흐름
Plaintext

[Request: ChatRequestDto]
       |
       v
[ChatService.continueChat] --- (요청 분석: RAG? Web? Standalone?)
       |
       +---------------------------------------------+
       |             [C. Retrieval ON (Hybrid)]      | (Default Flow)
       |                      |                      |
       |  [Context Gathering] <----+-----------------+-----------------+
       |   - Web Search (Naver)    |                 |                 |
       |   - RAG Search (Pinecone) |                 |                 |
       |   - Memory Load         [B. Retrieval OFF]  |                 |
       |           |                                 |                 |
       |           v                                 v                 |
       |  [buildUnifiedContext]             (Memory-only Context)      |
       |           |                                 |                 |
       |           v                                 |                 |
       |  [Caffeine Cache] -> chains.get(sessionKey) |                 |
       |           |                                 |                 |
       |           v                                 |                 |
       |  [ConversationalRetrievalChain.execute()]   |                 |
       |           |                                 |                 |
       |           +-------------------------------->+ [A. RAG Standalone]
       |                                             |      |
       v                                             |      v
[LLM Generates Answer] <-----------------------------+-- [ragSvc.getAnswer()]
       |
       v
[Final Answer: ChatResult]
🛠️ 주요 컴포넌트 (Key Components)
ChatService: 전체 비즈니스 로직을 관장하는 중앙 허브.

HybridRetriever: 웹 검색 결과와 RAG 검색 결과를 지능적으로 결합하는 커스텀 ContentRetriever.

NaverSearchService: 네이버 검색 API를 이용해 실시간 웹 정보를 수집.

LangChainRAGService: 벡터 데이터베이스에서 유사도 높은 문서를 검색.

FactVerifierService: 생성된 답변을 수집된 컨텍스트와 비교하여 사실 여부를 검증.

Caffeine Cache: 세션별 대화 체인을 저장하여 성능을 최적화하는 인메모리 캐시.

🔧 설정 (Configuration)
프로젝트를 실행하려면 application.yml 파일에 다음과 같은 설정을 구성해야 합니다.

YAML

# application.yml

# OpenAI API 설정
openai:
  api:
    key: "sk-..."  # OpenAI API 키
    model: "gpt-4o" # 기본으로 사용할 LLM 모델
    temperature:
      default: 0.7
    top-p:
      default: 1.0
    history:
      max-messages: 10 # 대화 기록 최대 개수
  fine-tuning:
    custom-model-id: "" # 파인튜닝된 모델 ID (선택 사항)
  
  # 컨텍스트별 최대 토큰 설정
  web-context:
    max-tokens: 8000 # 웹 검색 컨텍스트 최대 토큰
  rag-context:
    max-tokens: 5000 # RAG 컨텍스트 최대 토큰
  mem-context:
    max-tokens: 7500 # 메모리 컨텍스트 최대 토큰

# Pinecone (벡터 DB) 설정
pinecone:
  index:
    name: "my-knowledge-base" # 사용할 인덱스 이름

# 검색 관련 설정
search:
  official:
    # 공식 출처로 인정할 도메인 목록 (CSV)
    domains: "company-blog.com,official-docs.com"

# LangChain4j 설정 (필요시 추가)
langchain4j:
  # ...
🚀 실행 방법 (Getting Started)
리포지토리 클론:

Bash

git clone [repository-url]
cd [repository-name]
application.yml 설정:
src/main/resources/application.yml 파일에 자신의 API 키와 서비스 설정을 입력합니다.

빌드 및 실행:

Bash

./gradlew bootRun
(또는 IntelliJ IDEA 같은 IDE에서 애플리케이션을 실행합니다.)

이제 서비스가 시작되고 API 엔드포인트를 통해 챗봇 기능을 사용할 수 있습니다.