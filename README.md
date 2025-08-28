AbandonWare AI RAG Chatbot










이 저장소는 AbandonWareAI의 백엔드 챗봇 서버입니다. Gradle 기반의 Spring Boot 3.4.0 애플리케이션이며 Java 17과 LangChain4j 1.0.1을 사용합니다. 네이버·브레이브·타빌리 검색과 벡터 DB를 결합한 RAG 파이프라인을 제공하며, CoR 패턴을 통해 Self‑Ask → Analyze → Web → Vector 단계로 구성된 검색 체인을 갖습니다.
본 문서는 배포 수준의 운영 README로서 설치, 실행, 설정, 아키텍처, API 및 운영 가이드를 포괄합니다. 모든 명령은 Linux/macOS 기준입니다.
본 저장소는 내장된 규칙에 따라 LangChain4j 1.0.1 순도를 강제하며 환경변수 기반 비밀주입을 금지합니다. 키를 변경·삭제할 경우 빌드와 테스트가 실패합니다.

TL;DR — 주요 특징

CoR 체인: SelfAsk → Analyze → Web → VectorDb 순으로 핸들러가 실행됩니다. 증거 보수(EvidenceRepair)와 비용 가드(SearchCostGuard)가 내장되어 과도한 토큰 소비를 방지합니다.

프롬프트 빌더: 모든 프롬프트는 PromptBuilder.build(PromptContext)로 생성되며, 문자열 직접 연결은 금지됩니다. 첨부 파일 → RAG → 웹 → 히스토리 순으로 증거를 삽입합니다.

환경변수 금지: API 키는 src/main/resources/application‑secrets.yml 등 리소스 파일에만 저장하며, 시스템 환경변수를 통해 키를 주입하지 않습니다.

버전 순도 가드: Gradle resolutionStrategy와 StartupVersionPurityCheck가 LangChain4j 1.0.1만 허용합니다. 다른 버전이 발견되면 부팅 중단 후 STOP 가드가 발동합니다.

확장 가능 검색: Naver, Brave, Tavily 및 위키 제공자를 지원하며 도메인 필터·분류기가 포함되어 있습니다. ONNX 기반 리랭커를 통해 높은 정밀도를 제공합니다.

<!-- 아트 삭제: UI 스크린샷이나 ASCII 아트가 제공되지 않으므로 이 섹션을 생략합니다. -->
목차

A. 헤더 및 개요

B. 핵심 가치/특징

C. Quickstart

D. 의존성/버전 순도

E. 설정 가이드

F. 아키텍처 상세

G. RAG 파이프라인 & 검색

H. 프롬프트/컨텍스트 정책

I. 실행/운영/모니터링

J. API 가이드

K. 보안/키/비밀정보

L. 트러블슈팅

M. 성능/비용/품질

N. 기여/브랜치/PR 가이드

O. 라이선스/저작권/문서 메타

B. 핵심 가치/특징

이 섹션에서는 AbandonWareAI 프로젝트의 핵심 설계 원칙과 주요 기능을 설명합니다. 모든 기능은 실제 소스 코드와 일치하며, 운영 환경에서 적용할 때 유의해야 할 사항을 포함합니다.

CoR 검색 파이프라인 (4줄)

SelfAskHandler: 사용자의 질문을 분석해 키워드를 추출하고 사전 검색을 수행합니다.

AnalyzeHandler: 질의 분석 및 도메인/주제 감지를 수행하여 검색 범위를 제한합니다.

WebHandler: Naver/Brave/Tavily/Wiki 검색 제공자에서 웹 스니펫을 수집합니다.

VectorDbHandler: Pinecone 등 벡터 DB에서 RAG 문서를 검색하여 후속 프롬프트에 삽입합니다.

PromptBuilder 규칙 (6줄)

프롬프트 생성은 **PromptBuilder.build(PromptContext)**를 통해서만 수행합니다.

문자열을 직접 이어붙이는 concat 방식은 금지하고, 반드시 PromptContext를 이용해 각 섹션을 채웁니다.

섹션 순서는 첨부 (Attachments) → RAG 컨텍스트 → 웹 증거 → 대화 히스토리 → 메모리 순으로 고정되어 있습니다.

buildInstructions 메서드를 통해 시스템 지시와 어조(persona)를 추가할 수 있습니다.

MUST_INCLUDE 항목을 자동 추출하여 프롬프트의 마지막에 1~4개의 중요 단어를 포함합니다.

위치 정보가 제공될 경우 ### LOCATION CONTEXT 섹션이 추가됩니다.

세션 격리와 메모리 강화 (8줄)

세션 ID는 모든 RAG/웹/메모리 호출에 메타데이터로 전달되어 다른 세션의 자료를 혼용하지 않으며, 세션 종료 시 증거는 TTL에 따라 삭제됩니다.

RewardScoringEngine과 MemoryReinforcementService가 세션 격리를 보장하고, 장기 메모리를 요약 형태로 저장한 뒤 PromptBuilder에 삽입합니다.

rag.safety.enforceSid=true 설정을 통해 세션 격리를 강제하며, runRegistry.attach를 이용해 세션 복원 시 스트림을 재연결합니다.

메모리의 TTL과 인덱싱 정책은 rag.snippet.ttl-days 및 snippet.index-web-default로 조정할 수 있습니다.

보상 기반 강화를 위해 com.example.lms.scoring.PathAlignedScorer를 사용하며, 세션별 보상 스코어를 제공합니다.

세션 복원과 메모리 클리어 과정은 MemoryReinforcementService에서 관리합니다.

모든 세션 관련 메타데이터는 Vector DB와 로컬 DB에 별도로 저장되어 감사 추적을 지원합니다.

비용 가드 및 증거 보수 (8줄)

SearchCostGuardHandler는 분석 단계와 웹 검색 사이에서 예상 토큰 비용을 추정하여 임계값(router.moe.relief.threshold-tokens, 기본 12000)을 넘으면 “relief hint” 로그와 함께 하위 검색을 중단합니다.

EvidenceRepairHandler는 잘못된 도메인이나 부적절한 페이지에서 추출한 증거를 필터링·교체하고, 결과를 통합한 후 RAG와 웹 스니펫을 갱신합니다.

증거 보수는 retrieval.repair.domain 및 retrieval.repair.preferred-domains 설정을 활용하며, 잘못된 검색 결과를 제거하면 리랭커 후보 집합이 갱신됩니다.

비용 가드와 증거 보수 로직은 CoR 체인에서 별도의 핸들러로 동적으로 연결되며, 테스트 중에는 로그 레벨 DEBUG로 동작을 검증합니다.

비용 가드는 토큰 예산과 검색 깊이를 제한하여 외부 API 비용 폭주를 방지합니다.

증거 보수는 검색 결과의 신뢰성을 높여 프롬프트 품질을 개선하며, 고치기 어려운 문서는 즉시 폐기됩니다.

주요 파라미터는 search.cost.max-spend-tokens와 repair.max-candidate-mismatch에서 조정할 수 있습니다.

리랭킹 전략 (8줄)

기본 랭킹은 제공자별 점수를 Reciprocal Rank Fusion 방식으로 결합하여 초기 순위를 생성합니다.

ONNX cross‑encoder를 활성화하려면 abandonware.reranker.backend=onnx-runtime으로 설정하고, 모델을 classpath:/models/your-cross-encoder.onnx에서 로드합니다.

임베딩 기반 리랭커(embedding-model)는 LangChain4j의 임베딩 모델로 코사인 유사도를 계산하여 문서를 재정렬합니다.

리랭커를 비활성화하려면 abandonware.reranker.backend=noop로 설정하며, 두 리랭커 모두 상위 topK 문서만 반환합니다.

랭킹 후보 상한과 hard cap은 각 리랭커 구현에서 24~32개의 문서로 제한되어 있으며 최종 문서는 PromptContext에 삽입됩니다.

ONNX와 임베딩 리랭커의 성능 차이를 비교하려면 abandonware.reranker.debug=true를 설정하고 로그를 분석합니다.

리랭커는 벡터 DB 검색 이후 실행되어 품질을 향상하지만 추가 비용이 발생할 수 있습니다.

환경변수 사용 금지 정책 (10줄)

환경변수(System.getenv)를 통한 API 키나 비밀 정보 주입을 금지합니다.

모든 키는 src/main/resources 디렉터리의 YML/Properties 파일에서만 읽어야 하며, 운영 환경에서도 파일 기반으로 주입합니다.

로컬 개발 시 application-secrets.yml 파일을 생성하고 실제 키의 가운데 60~70 %를 별표로 마스킹합니다.

예시: openai.api.key: 처럼 중간 부분을 별표로 처리합니다.

키를 임의로 변경하거나 삭제하면 테스트와 검증 작업이 실패하므로 일관성을 유지해야 합니다.

비밀 정보가 포함된 파일은 Git에 커밋하지 말고 .sample 확장자로 샘플만 제공합니다.

spring.autoconfigure.exclude를 통해 LangChain4j 자동 구성을 비활성화하고 수동으로 키를 주입합니다.

운영 환경에서는 시크릿 매니저나 Vault를 통해 파일을 배포하며 환경변수 방식은 비활성화합니다.

lms.debug.mask-secrets=false와 같은 디버그 옵션을 조정하여 로그에 키가 노출되지 않도록 하고, 보안 정책을 준수하지 않으면 CI 파이프라인에서 빌드가 실패합니다.

구현 파일 포인터 (12줄)

버전 강제/금지: src/build.gradle
 파일의 resolutionStrategy 블록에서 LangChain4j 1.0.1 고정을 수행하고 com.theokanning/openai-service를 금지합니다.

RetrieverChainConfig: src/main/java/com/example/lms/config/RetrieverChainConfig.java
에서 SelfAsk → Analyze → CostGuard → Web → Vector 순으로 체인을 생성합니다.

StartupVersionPurityCheck: src/main/java/com/example/lms/boot/StartupVersionPurityCheck.java
에서 부팅 시 classpath MANIFEST를 검사하여 혼합 버전을 감지합니다.

PromptBuilder: src/main/java/com/example/lms/prompt/PromptBuilder.java
에서 프롬프트 구성 로직을 확인할 수 있습니다.

PromptContext: src/main/java/com/example/lms/prompt/PromptContext.java
는 불변 record로 정의된 컨텍스트 데이터 객체입니다.

SelfAskWebSearchRetriever: src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
는 Self‑Ask 검색과 휴리스틱 키워드 확장을 구현합니다.

NaverSearchService: src/main/java/com/example/lms/service/NaverSearchService.java
는 네이버 API 호출과 스니펫 추출을 담당합니다.

LangChainRAGService: src/main/java/com/example/lms/service/rag/LangChainRAGService.java
는 벡터 DB 검색과 재생성 API를 캡슐화합니다.

OnnxCrossEncoderReranker: src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java
는 ONNX 모델을 사용한 문서 리랭커입니다.

ChatApiController: src/main/java/com/example/lms/api/ChatApiController.java
는 채팅 API 엔드포인트를 제공합니다.

application.yml: src/main/resources/application.yml
에서 RAG, 검색, 라우팅, 세션 안전성 등의 기본 설정을 확인할 수 있습니다.

application-secrets.yml: src/main/resources/application-secrets.yml
는 비밀 키 저장소로, 항상 마스킹된 샘플만 커밋해야 합니다.

C. Quickstart

빠르게 프로젝트를 빌드하고 실행하는 방법을 안내합니다. 모든 명령은 리눅스/Mac 기준이며 윈도우 사용자는 ./gradlew 대신 gradlew.bat를 사용하면 됩니다.

요구사항 (6줄)

Java 17 SDK: java -version으로 버전을 확인합니다.

Gradle Wrapper: 저장소에 포함된 gradlew 스크립트를 사용합니다. 로컬에 Gradle을 설치할 필요는 없습니다.

포트 사용: 기본적으로 8080 포트를 사용하므로 다른 프로세스가 점유하고 있지 않은지 확인합니다.

인터넷 접속: 웹 검색과 모델 호출을 위해 외부 인터넷 연결이 필요합니다.

API 키 파일: application-secrets.yml을 프로젝트 루트 또는 src/main/resources에 생성하여 필요한 키를 주입해야 합니다.

Postman(선택): 제공된 POSTMAN 컬렉션을 사용하려면 Postman 앱이 필요합니다.

빌드 (10줄)

다음 명령으로 코드를 클론하고 빌드합니다. 첫 실행 시 Gradle이 의존성을 다운로드하므로 시간이 걸릴 수 있습니다.

# 저장소 클론 후 디렉터리 이동
git clone <repo-url> abandonware-ai
cd abandonware-ai/7_dark_src

# Gradle 빌드: 순도 검증과 테스트를 포함합니다
./gradlew clean build

# 빌드 결과는 build/libs 아래에 생성됩니다

실행 (8줄)

개발 모드가 아닌 ultra 프로필로 실행하려면 다음과 같이 합니다. 다른 프로필(예: dev)을 사용하려면 -Dspring.profiles.active=dev로 변경합니다.

# 스프링 부트 실행, ultra 프로필 활성화
./gradlew bootRun -Dspring.profiles.active=ultra

# 또는 빌드된 JAR로 실행
java -jar build/libs/*.jar --spring.profiles.active=ultra

구성 파일 배치 가이드 (24줄)

운영 키와 비밀 정보를 주입하려면 환경변수 대신 리소스 파일을 사용해야 합니다. 다음은 src/main/resources/application-secrets.yml 파일의 예시입니다.

spring:
  config:
    activate:
      on-profile: secrets

# Brave API 키 (예시)
search:
  brave:
    enabled: true
    api-key: 

# OpenAI/Groq 키 (예시)
openai:
  api:
    key: 

# Upstash Vector (예시)
upstash:
  vector:
    rest-url: "https://<YOUR_UPSTASH_VECTOR_ENDPOINT>"
    api-key: 
    namespace: "aw-default"
    read-only: true


위 파일에서 가운데 부분은 항상 별표(*)로 마스킹해야 하며, 실제 키는 절대로 커밋하지 않습니다. 또한 application-ultra.properties나 application-dev.properties에 디버그 설정을 추가할 수 있지만 키는 저장하지 마십시오.

Postman 컬렉션 사용 (10줄)

프로젝트 루트의 POSTMAN_COLLECTION.json
 파일을 Postman에 임포트하여 테스트를 자동화할 수 있습니다.

Postman을 열고 “Import”를 선택합니다.

POSTMAN_COLLECTION.json을 선택하면 “AbandonWareAI” 컬렉션이 생성됩니다.

Variables 탭에서 base_url을 http://localhost:8080으로 설정합니다.

세션 ID 및 모델 이름 등의 파라미터를 변수로 지정하여 여러 요청을 쉽게 전환할 수 있습니다.

컬렉션에는 /api/chat, /cancel, /state, /sessions 등 모든 주요 엔드포인트가 포함되어 있습니다.

빠른 E2E 테스트 (18줄)

다음 단계는 서비스를 빌드한 뒤 API를 호출하여 전체 흐름을 검증하는 예시입니다.

# 1. 첨부 파일 업로드 (예: sample.pdf)
curl -X POST http://localhost:8080/api/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/path/to/sample.pdf"

# 응답에서 attachmentIds를 추출합니다. 이 예에서는 ["att123"]라고 가정합니다.

# 2. 채팅 요청(동기)
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"안녕","attachmentIds":["att123"],"model":"gpt-mini"}'

# 3. 스트리밍 요청(SSE)
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"제조업 혁신 사례를 알려줘","sessionId":1}' --no-buffer


위 예시는 첨부 업로드 → 채팅 → 스트리밍 순서로 진행됩니다. --no-buffer 옵션을 사용하면 터미널에서 SSE 스트림을 실시간으로 볼 수 있습니다.

로그 확인 (10줄)

실행 중 로그는 기본으로 콘솔에 출력되며 logback 패턴은 application-ultra.properties에서 정의됩니다.

HTTP 요청/응답 상세 로그를 보려면 spring.webflux.log-request-details=true를 유지하고, logging.level.org.springframework.web=TRACE로 설정합니다.

ONNX self‑test 결과는 애플리케이션 시작 시 health/onnx_selftest.json을 통해 검증되며, 실패 시 WARN 로그가 출력됩니다.

RAG 검색 단계의 추적은 lms.debug.* 설정으로 제어되며, lms.debug.websearch.dump.curl=true를 통해 검색 API 호출을 cURL 형식으로 기록할 수 있습니다.

metrics는 metrics/samples.ndjson 파일에 ndjson 형식으로 저장되며, Grafana 등으로 수집할 수 있습니다.

흔한 실수 (10줄)

키 파일 누락 및 포트 충돌: application-secrets.yml을 생성하지 않거나 8080 포트를 다른 서비스가 사용하면 검색 API 호출이 실패합니다. 포트를 변경하고 키 파일을 준비하십시오.

환경변수 주입 및 혼합 버전: 키를 환경변수로 설정하거나 LangChain4j 0.2.x 모듈을 추가하면 purity 검사가 실패합니다. 의존성을 정리하고 application-secrets.yml만 사용하십시오.

ONNX 모델 누락: onnx-runtime을 활성화했지만 모델 파일이 없으면 초기화가 실패합니다. embedding-model로 변경하거나 모델을 추가하십시오.

D. 의존성/버전 순도

본 프로젝트는 LangChain4j 1.0.1 버전을 강제하며, 혼합 버전이나 금지된 OpenAI SDK 의존성을 탐지할 경우 빌드를 실패시킵니다. 아래 섹션에서는 Gradle 설정과 부트 단계 검사를 설명합니다.

LangChain4j 1.0.1 단일화 배경 (6줄)

LangChain4j는 0.x → 1.0.x 업그레이드를 거치면서 API가 크게 변경되었습니다. 혼합 버전이 classpath에 존재하면 런타임 오류가 발생하므로, 모든 dev.langchain4j 그룹 의존성을 1.0.1로 고정합니다. 또한, com.theokanning 그룹과 openai-service 모듈은 구 버전 OpenAI SDK를 포함하므로 사용이 금지됩니다.

Gradle 해상도 전략 발췌 (22줄)

아래 발췌는 src/build.gradle에서 버전 강제와 금지 의존성을 설정하는 부분입니다. 실제 경로를 주석으로 표기했습니다.

// src/build.gradle: LangChain4j version alignment and purity enforcement
configurations.all {
  resolutionStrategy.eachDependency { d ->
    if (d.requested.group == "dev.langchain4j") {
      useVersion("1.0.1")
    }
    if (d.requested.group == "com.theokanning" || (d.requested.name != null && d.requested.name.contains("openai-service"))) {
      throw new GradleException("Forbidden dependency detected: ${d.requested}")
    }
  }
}

dependencyManagement {
    imports {
        mavenBom "dev.langchain4j:langchain4j-bom:1.0.1"
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
        mavenBom "io.grpc:grpc-bom:1.70.0"
    }
}

tasks.named('check') {
    dependsOn("checkLangchain4jVersionPurity")
}


위 블록은 모든 dev.langchain4j 모듈을 1.0.1로 강제하고 com.theokanning 또는 openai-service가 포함된 의존성이 해상도 단계에서 즉시 예외를 발생시키도록 합니다.

StartupVersionPurityCheck 로직 요약 (10줄)

부팅 시 StartupVersionPurityCheck 컴포넌트가 classpath의 모든 META-INF/MANIFEST.MF를 검사하여 LangChain4j 모듈 버전을 확인합니다. Implementation-Title 또는 Automatic-Module-Name이 langchain4j로 시작하는 경우 Implementation-Version이 1.0.1 접두사를 만족해야 합니다. 혼재가 발견되면 다음과 같이 로그 후 예외를 던집니다:

Mixed LangChain4j detected (expected 1.0.1.*). Offenders: [langchain4j-core:0.2.18 @ /langchain4j-core-0.2.18.jar] -> Purge old/foreign artifacts and align BOM/core/starter/OpenAI to 1.0.1.


검증이 성공하면 LangChain4j purity OK 로그가 출력되고 애플리케이션이 계속 부팅됩니다.

혼합 버전 감지 STOP 규칙 (12줄)

빌드 단계: ./gradlew check는 checkLangchain4jVersionPurity 태스크를 실행하여 의존성 그래프를 탐색합니다. 0.2.x 버전이 발견되면 langchain4j-conflicts.txt 보고서를 생성하고 빌드를 실패합니다.

부트 단계: StartupVersionPurityCheck가 classpath를 검사하여 1.0.1과 다른 버전이 존재하면 즉시 IllegalStateException을 던져 부팅을 중단합니다.

STOP 가드: README나 문서에서 혼합 버전을 발견하면 즉시 리포트를 작성하고 더 이상 실행을 진행하지 않습니다. 개발자는 build.gradle의 의존성을 정리해야 합니다.

유지 관리: 라이브러리 업그레이드는 BOM과 해상도 전략을 동시 갱신해야 하며, 일부 모듈만 업데이트하는 것은 금지됩니다.

도구 통합: IDE에서 자동 제안된 버전 업그레이드가 있을 경우 반드시 Gradle BOM을 먼저 수정한 후 전체 테스트를 수행합니다.

문서화: 이러한 STOP 규칙은 본 README와 개발자 가이드에 명시되어야 하며, 신규 기여자는 이를 반드시 읽어야 합니다.

TheOkanning 금지 설명 (8줄)

com.theokanning 그룹과 openai-service 모듈은 구형 OpenAI Java SDK를 포함하여 LangChain4j 1.0.1과 호환되지 않습니다. Gradle resolutionStrategy에서 해당 그룹이 감지되면 즉시 GradleException을 발생시켜 빌드를 중단하며, 새 기능은 LangChain4j의 OpenAiChatModel 또는 OpenAiEmbeddingModel을 사용해야 합니다.

검증 태스크 사용법 (10줄)

./gradlew check – 단위 테스트, 버전 purity 검증, placeholder 감지, 오프라인 리플레이 평가를 모두 수행합니다.

./gradlew failOnPlaceholders – 소스 코드에서 ..., /* STUB */, // TODO 등의 플레이스홀더를 검색하고 발견 시 빌드를 실패시킵니다.

./gradlew offlineReplayCheck -PreplayLog=logs/sample.log – 오프라인 리플레이를 실행하여 NDCG/MRR 지표를 측정하고 최소 기준 미달 시 실패합니다.

./gradlew emitDependencyReport – 해상된 의존성 목록을 build/reports/dependency-report.txt에 출력합니다.

./gradlew emitClasspathJars – 런타임 클래스패스에 포함된 JAR 경로를 build/reports/classpath-jars.txt에 기록합니다.

E. 설정 가이드

이 절에서는 애플리케이션의 주요 설정 파일과 키 주입 원칙을 설명합니다. 모든 키는 리소스 파일에 저장하며, 환경변수는 사용하지 않습니다.

프로필 구조와 기본 파라미터 (12줄)

ultra 프로필: 운영 및 고성능 설정이 포함되며, 디버그 로그와 상세 HTTP 추적을 활성화합니다.

dev 프로필: 개발 및 로컬 테스트용이며, 캐시 TTL 축소와 낮은 top‑k 값이 설정되어 있습니다.

secrets 프로필: application-secrets.yml을 활성화하여 비밀 키를 주입합니다.

test 프로필: 테스트 실행 시 JPA 메모리 DB를 사용하고 외부 API 호출을 모의(mock)합니다.

프로필은 -Dspring.profiles.active=<profile> 또는 --spring.profiles.active=<profile>로 선택합니다.

여러 프로필을 쉼표로 구분하여 동시에 활성화할 수 있으나, secrets 프로필은 항상 마지막에 지정합니다.

application.yml 주요 키 설명 표 (12줄)
키	기본값	설명
abandonware.reranker.backend	embedding-model	리랭커 백엔드 선택 (embedding-model, onnx-runtime, noop)
abandonware.reranker.onnx.model-path	classpath:/models/your-cross-encoder.onnx	ONNX 모델 위치
abandonware.reranker.onnx.execution-provider	cpu	ONNX 실행 프로바이더 (cpu/gpu)
rag.search.top-k	10	벡터 DB 검색 상한
rag.precision.max-aggregate-chars	35000	RAG 문서 누적 최대 문자 수
rag.guard.min-evidence-count	2	재생성 전에 필요한 최소 증거 개수
naver.hedge.enabled	true	네이버 헷지(병렬 호출) 기능 활성화
naver.fallback.duckduckgo.enabled	${NAV_FALLBACK_DDG:true}	DuckDuckGo 스크래핑 폴백
router.moe.tokens-threshold	280	Mixture‑of‑Experts 업그레이드 토큰 기준
router.moe.complexity-threshold	0.55	질의 복잡도 기준
router.moe.uncertainty-threshold	0.35	불확실성 기준
application-secrets.yml 샘플 코드블록 (24줄)

아래는 비밀 파일의 구조 예시입니다. 실제 키는 가운데를 마스킹하고, 예시 값은 삭제하거나 별도 시크릿 매니저에 저장하세요.

spring:
  config:
    activate:
      on-profile: secrets

# Brave API 키
search:
  brave:
    enabled: true
    api-key: 

# Groq/OpenAI 키
openai:
  api:
    key: 

# Upstash Vector 키
upstash:
  vector:
    rest-url: "https://<YOUR_UPSTASH_VECTOR_ENDPOINT>"
    api-key
    namespace: "aw-default"
    read-only: true

키 주입 원칙 (12줄)

리소스 파일만 사용: 모든 API 키와 비밀은 application-secrets.yml 또는 application-ultra.properties 같은 리소스 파일에서 읽어야 합니다.

환경변수 금지: System.getenv를 호출하거나 운영 시스템에서 키를 환경변수로 주입하지 않습니다. 코드 전반에서 환경변수를 사용하지 않는지 검토해야 합니다.

샘플 파일 제공: 공개 저장소에는 application-secrets.sample.yml 같은 샘플 파일을 제공하고, 실제 키는 각자의 환경에서 복사하여 사용합니다.

키 변경 금지: 기본 키는 테스트와 리플레이 로그에 종속되어 있으므로, 임의로 변경하거나 삭제하지 말아야 합니다. 변경 시 CI 검증이 실패합니다.

Vault/Secret Manager 사용: 운영 배포 시 쿠버네티스 Secret 또는 HashiCorp Vault 등 외부 비밀관리 시스템에서 파일을 마운트하여 주입합니다.

출력 마스킹: 디버그 로그에 키가 출력되지 않도록 lms.debug.mask-secrets=false를 검토하고 필요 시 true로 설정합니다.

권한 분리: 개발 환경에서는 최소 권한 키를 사용하고, 운영 환경에서만 전체 권한 키를 사용합니다.

확장자 주의: Properties 파일의 경우 .properties 확장자를 사용하며, YAML 파일은 .yml 형식으로 작성합니다.

문서화: 키 관리 정책은 README, Wiki, 내부 정책 문서에 반복적으로 명시해야 합니다.

검수: PR 검토 시 비밀 키가 커밋되지 않았는지 확인하는 체크리스트를 포함합니다.

검색 한도/타임아웃/캐시 튜닝 (18줄)

web-top-k: selfask.web-top-k, analyze.web-top-k, naver.search.web-top-k 등의 값을 조절하여 검색 스니펫 수를 제한할 수 있습니다. 기본값은 8~10입니다.

overall-top-k: SelfAsk 전체 반환 상한을 selfask.overall-top-k로 설정합니다. 권장 값은 10 이내입니다.

타임아웃: selfask.timeout-seconds(디폴트 12)와 analyze.timeout-seconds(디폴트 5)를 조절하여 검색 단계의 최대 대기 시간을 설정합니다. 개별 검색 타임아웃은 per-request-timeout-ms로 제어합니다.

캐싱: Naver 검색 결과는 Caffeine 캐시를 사용하며, TTL은 search.keyword.selected-terms.cache-ttl-minutes를 통해 제어합니다. 필요 시 cache.refresh-after-write 옵션을 추가할 수 있습니다.

페이지 추출 시간: search.budget.per-page-ms는 웹 페이지 본문 추출 시 타임박스(ms)를 설정합니다. 기본값은 3500ms이며, 느린 페이지에서 타임아웃을 방지합니다.

세션 TTL: RAG 스니펫의 TTL은 rag.snippet.ttl-days(기본 14일)로 관리됩니다. 이 값을 줄이면 메모리 사용량을 줄일 수 있습니다.

벡터 인덱싱: rag.index.transient-namespace를 사용하여 임시 네임스페이스를 변경할 수 있습니다. 프로덕션 환경에서는 고유한 네임스페이스를 사용해야 합니다.

Search Budget: selfask.max-api-calls-per-query와 analyze.max-api-calls-per-query는 검색 호출 상한입니다. 무한 루프를 방지하기 위해 적절한 값을 설정합니다.

쿼리 추출: 검색 키워드 추출 모드는 search.extractor.mode (RULE, LLM, HYBRID, AUTO) 중에서 선택합니다. AUTO 모드는 복잡도에 따라 모드를 결정합니다.

LLM 백엔드: search.llm.backend를 gemini 또는 lc4j로 설정하여 키워드 추출 모델을 선택합니다. gemini를 사용할 경우 환경 변수에 키를 제공해야 합니다 (파일 기반 권장).

도메인 필터: naver.filters.domain-policy를 boost, filter, off 중 하나로 조절하여 도메인 선호도 정책을 선택합니다.

위험 회피: search.accumulation.enabled=false로 설정하여 대규모 데이터 축적 모드를 비활성화할 수 있습니다. 축적 모드를 켤 경우 관련 파라미터를 조절해야 합니다.

ONNX 리랭커 설정 발췌 (14줄)

다음 코드는 application.yml의 ONNX 섹션 일부를 발췌한 것입니다. 실행 프로바이더와 모델 경로를 확인하세요.

abandonware:
  reranker:
    backend: onnx-runtime  # 임베딩 모델 대신 ONNX cross-encoder 사용
    onnx:
      model-path: classpath:/models/your-cross-encoder.onnx
      execution-provider: cpu
      vocab-path: classpath:/models/vocab.txt
      max-seq-len: 256
      normalize: true

금지 패턴 (8줄)

System.getenv 사용 금지: 코드에서 System.getenv를 호출하지 말고, 환경변수에 의존하지 않습니다.

플레이스홀더 사용 금지: 소스 코드에 ... 또는 // TODO가 포함되면 ./gradlew failOnPlaceholders 태스크에서 실패합니다.

금지 의존성 추가 금지: com.theokanning 또는 openai-service 계열 라이브러리는 추가하지 않습니다.

직접 HTTP 호출 금지: 검색이나 모델 호출은 전용 서비스(NaverSearchService, LangChainRAGService)를 통해 수행해야 하며, 컨트롤러에서 직접 호출하지 않습니다.

문자열 concat 프롬프트 금지: 프롬프트는 항상 PromptBuilder를 사용하여 구성합니다.

비밀 키 하드코딩 금지: 소스 코드에 직접 키를 작성하면 보안 취약점이 발생합니다.

장기 캐시 미설정 금지: 캐시 TTL을 지나치게 길게 설정하면 오래된 데이터가 남아있을 수 있습니다.

대량 병렬 호출 금지: 검색 API는 QPS 제한이 있으므로 병렬 호출 수를 제한합니다.

설정 체크리스트 (10줄)

application.yml과 application-secrets.yml이 같은 디렉터리에 위치하는지 확인한다.

spring.autoconfigure.exclude에서 LangChain4j 자동 구성을 제외했는지 검토한다.

프로파일별 속성을 application-ultra.properties 등으로 분리하여 관리한다.

ONNX 모델이 존재하고 abandonware.reranker.backend=onnx-runtime이 맞는지 확인한다.

Naver API 키와 Brave API 키가 모두 설정되어 있는지 확인한다.

검색 상한(top‑k)과 타임아웃을 요구사항에 맞게 조정하였다.

데이터베이스 설정(MariaDB URL/계정)이 application.yml에 정확히 기입되었다.

router.moe.* 설정으로 모델 라우팅 기준이 적절한지 검토한다.

rag.safety.enforceSid=true로 세션 격리가 활성화되어 있는지 확인한다.

비밀 키 파일이 Git ignore에 포함되어 있는지 마지막으로 확인한다.

F. 아키텍처 상세

AbandonWareAI의 내부 구조를 다이어그램과 표로 설명합니다. 전체 레이어 구성, 핸들러 체인, 프롬프트 흐름 및 클래스 간 의존 관계를 이해하면 유지보수와 확장이 수월해집니다.

전체 레이어도 (Mermaid 32줄)

다음 다이어그램은 클라이언트부터 데이터 레이어까지의 주요 컴포넌트를 한눈에 보여줍니다.

flowchart TB
  subgraph Client[Client Layer]
    WebUI[Web UI]
    Postman[Postman/CLI]
  end

  subgraph Controller[Controllers]
    ChatApiController[/ChatApiController/]
    UploadController[/UploadController/]
  end

  subgraph Service[Services]
    ChatService
    RetrieverChainConfig
    MemoryReinforcementService
    PromptBuilder
  end

  subgraph CoR[Retrieval Handlers (CoR)]
    SelfAskHandler --> AnalyzeHandler --> WebHandler --> VectorDbHandler
    SearchCostGuardHandler -. guard .- WebHandler
    EvidenceRepairHandler -. fix .- VectorDbHandler
  end

  subgraph Data[Data & External]
    LMSDB[(MariaDB)]
    VectorDB[(Pinecone)]
    NaverAPI[(Naver Search)]
    BraveAPI[(Brave/Tavily)]
    OpenAI[(OpenAI via LC4J 1.0.1)]
  end

  WebUI --> ChatApiController --> ChatService
  ChatService --> RetrieverChainConfig --> SelfAskHandler
  VectorDbHandler --> VectorDB
  WebHandler --> NaverAPI
  WebHandler --> BraveAPI
  ChatService --> OpenAI
  ChatService --> MemoryReinforcementService
  ChatService --> LMSDB

레이어 설명 (16줄)

Client Layer: 웹 UI 또는 CLI/Postman이 REST API를 호출하여 사용자 요청을 전달합니다.

Controller Layer: ChatApiController와 UploadController는 채팅 및 파일 업로드를 위한 HTTP 엔드포인트를 제공합니다.

Service Layer: ChatService는 채팅 로직을 조정하며, RetrieverChainConfig를 통해 검색 핸들러 체인을 초기화합니다. MemoryReinforcementService는 세션별 메모리 강화를 담당하고, PromptBuilder는 프롬프트를 생성합니다.

Retrieval Handlers (CoR): SelfAsk, Analyze, Web, Vector 핸들러는 체인으로 연결되어 있으며 각 단계는 다음 단계에 링크됩니다. 가드와 보수 핸들러는 점선으로 표시됩니다.

Data & External Layer: MariaDB는 채팅 세션과 히스토리를 저장하고, Pinecone (VectorDB)는 RAG 문서를 인덱싱합니다. NaverAPI, BraveAPI, Tavily는 웹 검색을 제공하며, OpenAI 또는 Groq 모델을 통해 답변을 생성합니다.

각 레이어는 느슨하게 결합되어 있어 특정 구성 요소를 교체하기 쉽습니다. 예를 들어, Pinecone를 다른 벡터 스토어로 교체하거나 NaverAPI를 비활성화할 수 있습니다.

WebUI는 SSE(stream)을 통해 채팅 응답을 실시간으로 수신합니다. SSE 연결은 ChatStreamEmitter가 관리합니다.

UploadController는 첨부 파일을 수신하고 AttachmentService를 통해 저장합니다.

주요 클래스 경로 테이블 (16줄)
컴포넌트	설명	파일 경로
ChatApiController	채팅/스트림/세션 관리 API	src/main/java/com/example/lms/api/ChatApiController.java
RetrieverChainConfig	SelfAsk→Analyze→Web→Vector 체인 구성	src/main/java/com/example/lms/config/RetrieverChainConfig.java
ChatService	채팅 비즈니스 로직, 모델 호출	src/main/java/com/example/lms/service/ChatService.java
SelfAskWebSearchRetriever	Self‑Ask 검색 실행	src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
AnalyzeWebSearchRetriever	질의 분석 후 웹 검색	src/main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java
WebSearchRetriever	Naver/Brave/Tavily 래퍼	src/main/java/com/example/lms/service/rag/WebSearchRetriever.java
LangChainRAGService	벡터 DB 및 LangChain4j 호출	src/main/java/com/example/lms/service/rag/LangChainRAGService.java
OnnxCrossEncoderReranker	ONNX 기반 리랭크 서비스	src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java
EvidenceRepairHandler	증거 보수 로직	src/main/java/com/example/lms/service/rag/handler/EvidenceRepairHandler.java
SearchCostGuardHandler	비용 가드 핸들러	src/main/java/com/example/lms/service/rag/handler/SearchCostGuardHandler.java
PromptBuilder	프롬프트 생성기	src/main/java/com/example/lms/prompt/PromptBuilder.java
PromptContext	프롬프트 컨텍스트 DTO	src/main/java/com/example/lms/prompt/PromptContext.java
의존 관계 및 라우팅 흐름 (16줄)

모든 검색 핸들러는 RetrieverChainConfig에서 생성되며, linkWith 메서드로 다음 핸들러를 연결합니다.

SelfAsk 핸들러는 질문을 요약하고 키워드를 추출하여 1차 웹 검색을 실행합니다. Follow‑up 질의는 max-depth와 followups-per-level 파라미터로 제어됩니다.

Analyze 핸들러는 도메인 감지와 설명형 질의 분석을 통해 키워드를 확장하거나 축소합니다. Query Complexity Gate가 이 단계와 연결되어 복잡한 질의는 SelfAsk를 다시 호출할 수 있습니다.

Web 핸들러는 AdaptiveWebSearchHandler를 통해 NaverProvider, BraveProvider, TavilyProvider 등 여러 검색기를 호출하고 결과를 병합합니다.

Vector 핸들러는 Pinecone 등 벡터 DB를 조회하여 RAG 문서를 가져옵니다. pinecone.index.name 프로퍼티로 인덱스를 지정할 수 있습니다.

EvidenceRepairHandler는 Vector 핸들러 뒤에서 실행되어 잘못된 증거를 교정합니다.

SearchCostGuardHandler는 Analyze와 Web 사이에 삽입되어 예상 토큰 수가 임계값을 넘는지 검사합니다.

RetrievalOrderService는 핸들러 순서를 동적으로 재구성하는 기능을 제공하지만, 기본 구성에서는 SelfAsk→Analyze→Web→Vector가 고정됩니다.

Retrieval CoR 다이어그램 (Mermaid 28줄)

아래 다이어그램은 사용자 질의가 핸들러를 통과하는 과정을 설명합니다. 점선은 가드 또는 보수 핸들러를 나타냅니다.

flowchart LR
  Q[User Query] --> SA[SelfAskHandler]
  SA --> AZ[AnalyzeHandler]
  AZ --> WEB[WebHandler]
  WEB --> VEC[VectorDbHandler]

  %% Guards & Aux
  WEB -.-> COST[SearchCostGuardHandler]
  VEC -.-> FIX[EvidenceRepairHandler]

  %% Inputs/Outputs
  subgraph Inputs
    Attach[Attachments]
    Aliases[Alias Dict]
  end
  Attach --> AZ
  Aliases --> AZ

  subgraph Outputs
    WebSnippets[Web Snippets]
    RagDocs[RAG Docs]
    FinalCtx[PromptContext]
  end
  WEB --> WebSnippets
  VEC --> RagDocs
  RagDocs --> FinalCtx
  WebSnippets --> FinalCtx

EvidenceRepair/SearchCostGuard 위치 설명 (12줄)

SearchCostGuardHandler는 Web 핸들러 앞에서 실행되며, 예상되는 토큰 소비를 계산합니다. 사용자의 질의 길이와 현재 thresholdTokens 값에 따라 검색을 스킵하거나 축소합니다.

Guard는 로그 채널 guard를 사용하여 힌트를 남기며, 임계값은 router.moe.relief.threshold-tokens로 설정할 수 있습니다.

EvidenceRepairHandler는 Vector 핸들러 뒤에서 실행되어 잘못된 증거를 제거하거나 유사한 증거로 대체합니다. 이를 통해 프롬프트의 품질을 향상시킵니다.

EvidenceRepair는 선호 도메인과 금지 도메인을 retrieval.repair.preferred-domains와 retrieval.repair.domain 설정에서 읽습니다.

두 핸들러 모두 RetrieverChainConfig에서 빈으로 주입되며, 기본 CoR 순서에 자동으로 연결됩니다.

커스텀 가드나 보수 핸들러를 추가하려면 DefaultRetrievalHandlerChain을 확장하여 linkWith 호출을 수정합니다.

프롬프트 플로우 시퀀스 (Mermaid 22줄)

다음 시퀀스 다이어그램은 프롬프트가 생성되고 LLM으로 전달되는 과정을 보여줍니다. 문자열 concat은 금지되고, PromptBuilder가 모든 섹션을 결합합니다.

sequenceDiagram
  participant U as User
  participant C as ChatApiController
  participant S as ChatService
  participant PB as PromptBuilder
  participant PC as PromptContext

  U->>C: POST /api/chat (message + attachmentIds)
  C->>S: chat(dto)
  S->>PC: assemble(attachments, rag, web, history)
  S->>PB: build(PC)  %% 문자열 concat 금지
  PB-->>S: Prompt
  S->>RAG: HybridRetriever.retrieve(PC)
  RAG-->>S: {localDocs, ragDocs, webSnippets}
  S->>PB: rebuild(PC+)  %% 순서: ATTACH → RAG → WEB → HISTORY
  PB-->>S: Final Prompt
  S->>LLM: chat(prompt) via LC4J 1.0.1
  LLM-->>S: answer
  S-->>C: SSE/JSON

G. RAG 파이프라인 & 검색

이 절에서는 검색 및 증거 수집의 흐름을 자세히 설명합니다. SelfAsk, Analyze, Web, Vector 단계의 역할과 리랭킹, 필터링, 리플레이 로깅을 포함합니다.

단계별 역할 (20줄)

SelfAsk 단계: 사용자가 직접 입력한 질의를 분석하여 길이, 단어 수, WH 패턴을 기반으로 검색 깊이(1~3)와 follow‑up 키워드를 결정합니다. 첫 번째 검색에서 충분한 결과가 발견되면 SelfAsk를 조기에 종료합니다.

Analyze 단계: 질문의 도메인을 감지하고, 게임/학습/일반 등 주제를 식별합니다. GameDomainDetector와 QueryContextPreprocessor가 의도 및 주제를 추출하여 검색 범위를 좁힙니다.

Web 단계: AdaptiveWebSearchHandler가 네이버, 브레이브, 타빌리, 위키 등 여러 검색 제공자를 호출하고 결과를 통합합니다. 각 제공자는 WebSearchProvider 인터페이스를 구현합니다.

Vector 단계: LangChainRAGService가 Pinecone 인덱스에서 관련 문서를 조회하여 반환합니다. 반환된 문서와 웹 스니펫은 리랭커에 의해 재정렬됩니다.

각 단계는 실패에 대해 fail‑soft 설계를 갖습니다. 예를 들어, 네이버 API 키가 없으면 Brave/Tavily 제공자로 넘어갑니다.

Chain of Responsibility 패턴 덕분에 필요에 따라 새로운 핸들러를 삽입하거나 제거할 수 있습니다.

SelfAsk 단계의 키워드 추출은 오타 교정과 금칙어 필터링을 포함하며, max-api-calls-per-query를 초과하면 중단합니다.

Analyze 단계는 별도의 LLM 호출 없이 휴리스틱 규칙을 사용합니다. 미지원 키워드는 unsupportedClaims 목록으로 전달되어 프롬프트에 표시됩니다.

Web 단계는 도메인 필터와 Boost 정책을 적용하여 선호하는 도메인의 페이지를 우선시합니다.

Vector 단계는 관련도가 낮은 문서를 제거하기 위해 rag.guard.min-evidence-count와 rag.guard.min-evidence-from-distinct-sources를 사용합니다.

Naver/Brave/Tavily 사용 위치 (16줄)

NaverProvider: 가장 우선 순위가 높으며, naver.search.web-top-k만큼의 스니펫을 가져옵니다. DuckDuckGo 스크래핑은 기본적으로 비활성화되어 있으며, naver.fallback.duckduckgo.enabled=true로 변경할 수 있습니다.

BraveProvider: Brave API는 QPS 제한이 낮으므로 search.brave.qps 값을 준수해야 합니다. 키가 없으면 자동으로 비활성화됩니다.

TavilyProvider: 검색 지원이 제한적일 때 사용되는 선택적 제공자로, tavily.enabled=false가 기본값입니다.

WikiProvider: 위키백과에서 요약 정보를 가져옵니다. wiki.enabled=true와 wiki.lang=ko 설정으로 언어를 지정할 수 있습니다.

도메인 필터: naver.filters.domain-policy를 boost로 설정하면 .go.kr, .ac.kr, hoyoverse.com, hoyolab.com 등 선호 도메인에 가중치를 부여합니다. 도메인 허용 목록은 naver.filters.domain-allowlist에서 정의합니다.

주제 분류: SubjectResolver가 검색 결과를 주제별로 그룹화하고, 관련 없는 주제는 제거합니다.

Fallback 순서: Naver API 오류 → Brave API → Tavily API → 빈 목록 순으로 fail‑soft가 적용됩니다.

검색 로그: 각 검색 단계는 SearchTrace와 SearchStep 객체에 기록되며, 디버깅 시 searchStep 로그를 확인할 수 있습니다.

리랭킹/샘플 점수표 (14줄)

검색 결과는 RRF 점수와 리랭커 점수를 결합하여 정렬됩니다. 아래는 예시 점수표입니다.

DocID	Provider	RRF Score	ONNX Score	Final Rank
1	Naver	0.45	0.82	1
2	Brave	0.40	0.75	2
3	Tavily	0.38	0.70	3
4	VectorDB	0.35	0.65	4
5	Naver	0.30	0.55	5

RRF 점수는 각 제공자에서의 순위에 따라 계산되며, ONNX 리랭커는 질의와 후보 문서 간의 의미적 유사도를 측정하여 점수를 제공합니다. 리랭커가 비활성화되어 있으면 Final Rank는 RRF Score 순으로 결정됩니다.

데이터 라인리지 다이어그램 (Mermaid 20줄)

검색 → 리랭크 → 프롬프트 구성 과정에서 데이터가 어떻게 흐르는지 다음 다이어그램으로 나타냅니다.

flowchart TB
  QRY[Query/History]
  ATT[Attachments]
  ALIAS[Alias/YAML]
  WEB[Web Search Snippets]
  VEC[RAG Vector Hits]
  RERANK[Re-Ranker (Embedding/ONNX)]
  CTX[PromptContext]
  PROMPT[PromptBuilder → Prompt]
  LLM[LC4J 1.0.1 ChatModel]

  QRY --> WEB
  QRY --> VEC
  ATT --> CTX
  ALIAS --> WEB
  WEB --> RERANK
  VEC --> RERANK
  RERANK --> CTX
  CTX --> PROMPT --> LLM

품질 가드 (18줄)

토큰 예산 가드: LLM 호출 전에 예상 토큰 수를 계산하고, 예산을 초과하면 작은 모델이나 축약 답변으로 자동 다운그레이드합니다. 예산은 generationParams.targetTokenBudgetOut으로 설정합니다.

노이즈 컷: 검색 결과에서 스팸이나 무관한 스니펫을 제거하기 위해 키워드 필터와 도메인 필터를 적용합니다. naver.filters.domain-policy=filter로 엄격 모드를 설정할 수 있습니다.

Evidence Guard: rag.guard.min-evidence-count와 min-evidence-from-distinct-sources는 적어도 N개의 증거와 S개의 서로 다른 출처가 존재해야 RAG가 실행되도록 합니다.

Fail‑Soft: 모든 검색 및 리랭크 단계는 예외를 던지지 않고 빈 목록을 반환합니다. 따라서 한 공급자가 실패해도 다른 공급자의 결과가 사용됩니다.

Retry 정책: search.brave.retry나 naver.search.retry 설정을 통해 외부 API 호출에 대한 재시도 횟수와 백오프를 지정할 수 있습니다.

캐시 TTL: 검색 결과와 키워드 추출 결과는 Caffeine 캐시에 저장되어 중복 호출을 줄입니다. 캐시 TTL은 search.keyword.selected-terms.cache-ttl-minutes 등으로 조절합니다.

멀티 소스 퓨전: WeightedRrfFuser는 로케일·언어·권위·최근성 가중치를 조합하여 여러 소스의 결과를 통합합니다.

로깅: 검색 단계별 지표는 Micrometer를 통해 수집할 수 있으며, 추적 ID(traceId)가 로그에 포함되어 있습니다.

ONNX health check: 시작 시 health/onnx_selftest.json이 로딩되어 모델 출력이 예상 범위인지 검증하며, 실패하면 리랭커가 비활성화됩니다.

테스트 리플레이/샘플 로그 해설 (12줄)

logs/sample.log는 오프라인 리플레이 테스트를 위한 예시 로그입니다. ./gradlew offlineReplayCheck -PreplayLog=logs/sample.log -Preplay.ndcgMin=0.25 -Preplay.mrrMin=0.25 명령으로 평가를 실행합니다.

리플레이 러너는 각 질의에 대해 검색, 리랭크, 프롬프트 생성, 답변을 재현하고, 정답 데이터셋과 비교하여 NDCG 및 MRR 지표를 계산합니다.

결과는 build/reports/offline-replay 디렉터리에 저장됩니다. 기준 미만이면 빌드가 실패합니다.

샘플 로그에는 Query, RetrievedDocs, WebSnippets, FinalAnswer 항목이 JSON 형식으로 기록되어 있어 디버깅에 활용할 수 있습니다.

H. 프롬프트/컨텍스트 정책

프롬프트와 컨텍스트를 안전하고 일관되게 구성하기 위한 정책을 설명합니다. PromptContext의 필드 정의와 섹션 순서, 예시 블록을 포함합니다.

PromptContext 필드 정의 표 (12줄)
필드	타입	설명
userQuery	String	현재 사용자 질문
lastAssistantAnswer	String	이전 챗봇 답변
history	String	이전 대화 기록(요약)
web	List<Content>	라이브 웹 검색 결과
rag	List<Content>	벡터 DB 검색 결과
memory	String	장기 메모리 스니펫
fileContext	String	업로드된 파일의 텍스트
location	LocationSection	사용자의 위도/경도/정확도
locationAddress	String	위치의 사람용 주소
섹션 순서 (10줄)

프롬프트는 다음 순서로 섹션을 구성해야 합니다. 순서를 어기면 RAG 우선순위가 뒤바뀌어 잘못된 답변을 유도할 수 있습니다.

ATTACHMENTS: 업로드된 파일에서 추출한 로컬 문서. 다른 어떤 증거보다 우선합니다.

RAG CONTEXT: 벡터 DB에서 검색된 문서. 중요도가 높으며, 최신성이 보장되지 않는 웹보다 먼저 배치됩니다.

WEB CONTEXT: 라이브 웹 검색에서 추출한 스니펫. 도메인 필터와 리랭커가 적용된 결과입니다.

CONVERSATION HISTORY: 최근 대화 내역. 너무 길면 요약되어 제공됩니다.

LONG‑TERM MEMORY: 과거 세션에서 강화된 메모리. 필요에 따라 삽입됩니다.

예시 프롬프트 블록 (18줄)

다음은 PromptBuilder가 생성하는 프롬프트 예시입니다. 실제 생성 시에는 MUST_INCLUDE 단어가 자동으로 추출됩니다.

### ATTACHMENTS
1. (요약) "자료1.pdf"에서 추출된 텍스트 …

### RAG CONTEXT
- [Doc123] "임베딩 기반 검색이란 …" (출처: 벡터DB)
- [Doc456] "검색 랭킹의 역순 융합에 대한 설명 …" (출처: 벡터DB)

### WEB CONTEXT
- "네이버 검색: 랭크 융합 알고리즘 사례 …"
- "Brave 검색: ONNX 리랭커 성능 …"

### CONVERSATION HISTORY
User: 안녕?
Assistant: 안녕하세요! 무엇을 도와드릴까요?

### LONG-TERM MEMORY
…
### MUST_INCLUDE
- 임베딩, 융합, 랭커

### USER
임베딩 기반 검색과 ONNX 리랭커의 차이를 설명해줘.

금지 패턴 (10줄)

문자열 연결 금지: 프롬프트를 직접 문자열로 더하지 말고 반드시 PromptBuilder를 이용한다.

Null 체크 누락 금지: PromptContext의 nullable 필드를 검증하여 NPE를 방지한다.

동적 섹션 삽입 금지: 섹션 순서는 고정이며 중간에 다른 섹션을 끼워 넣지 않는다.

사용자 입력 무검증 금지: 오타 교정과 필터링을 거치지 않은 입력을 프롬프트에 삽입하지 않는다.

대량 증거 삽입 금지: TokenClipper를 사용하여 섹션별 길이를 제한하고 토큰 예산을 준수한다.

버전 혼합 금지: LangChain4j 0.2.x API 호출을 포함하면 안 되며 1.0.1 API만 사용한다.

시스템 인스트럭션 누락 금지: 특정 모드에 필요한 systemInstruction 필드를 반드시 지정한다.

레거시 플레이스홀더 방치 금지: 프롬프트 형식에 ...가 남아있으면 failOnPlaceholders 태스크가 실패한다.

테스트 팁 (10줄)

PromptBuilder 출력에서 섹션 헤더가 첨부→RAG→WEB→HISTORY 순서로 배치되는지 확인한다.

MUST_INCLUDE 섹션에 최대 4개의 중요한 단어만 포함되고, 빈 web 또는 rag 리스트일 때 섹션이 생략되는지 확인한다.

location과 locationAddress가 함께 제공될 때 둘 모두 LOCATION CONTEXT에 출력되는지 테스트한다.

오타 교정과 금칙어 필터링이 제대로 수행되어 사용자 입력이 프롬프트에 안전하게 반영되는지 검증한다.

프롬프트에 포함되는 문서 ID와 출처가 정확하며 토큰 길이가 한도(targetTokenBudgetOut)를 넘지 않는지 확인한다.

systemInstruction이 CORRECTIVE_REGENERATION인 경우 DRAFT 섹션이 추가되는지, follow‑up 질의가 없을 때 PREVIOUS_ANSWER 섹션이 누락되는지 검사한다.

프롬프트 생성 중 예외가 발생하면 빈 문자열을 반환하여 시스템이 안전하게 복구되는지 확인한다.

임베딩 기반 검색과 ONNX 리랭커의 차이를 설명할 수 있도록 테스트 케이스를 작성하여 품질 차이를 평가한다.

I. 실행/운영/모니터링

애플리케이션을 운영 환경에서 실행할 때 필요한 헬스 체크, 모니터링, 로그 관리 방법을 설명합니다. 장애 발생 시 조치 및 체크리스트도 포함합니다.

헬스 인디케이터 (10줄)

ONNX Self‑Test: health/onnx_selftest.json의 passed=true 여부로 교차 인코더 모델 로딩 상태를 확인한다.

Actuator Health: management.endpoints.web.exposure.include=health,info,httptrace,beans,env로 /actuator/health를 노출하고 값이 “UP”인지 점검한다.

LLM Health: LlmHealthIndicator가 llm.* 및 openai.* 프로퍼티를 검사하여 API 키 유효성을 확인한다.

Database Health: DataSourceHealthIndicator로 MariaDB 연결 상태를 확인하고 장애 시 “DOWN” 상태를 처리한다.

Redis/Vector Health: Upstash Redis 또는 Vector 서비스에 대해 HealthIndicator를 구현하여 연결을 검증한다.

Scheduler Health: 백그라운드 스케줄러(TaskScheduler) 실패 여부를 모니터링하고 필요 시 경고를 설정한다.

Custom Checks: Gemini/Tavily 등 외부 종속성에 대한 HealthIndicator를 구현하여 상태를 모니터링한다.

헬스 체크 실패 시 Kubernetes Probe 또는 로드밸런서가 자동으로 트래픽을 분산하도록 설정한다.

로그/메트릭 경로 (8줄)

로그 출력: 기본 logback 설정은 콘솔로 출력되며, 파일 로깅은 비활성화되어 있습니다. 파일 로깅을 원한다면 logback-spring.xml에 파일 앱렌더를 추가하십시오.

메트릭 파일: metrics/samples.ndjson에 ndjson 형식의 메트릭 샘플이 기록됩니다. 이는 Grafana Loki나 Prometheus Pushgateway로 전송할 수 있습니다.

테스트 커버리지: Jacoco 보고서는 coverage/jacoco-report/index.html에서 확인할 수 있으며, SonarQube 분석에 활용할 수 있습니다.

오프라인 리플레이 리포트: build/reports/offline-replay 폴더에 NDCG/MRR 결과가 저장됩니다.

의존성 보고: build/reports/dependency-report.txt에는 모든 런타임 의존성과 버전이 기록됩니다.

클래스패스 보고: build/reports/classpath-jars.txt는 런타임 클래스패스에 포함된 JAR 목록을 나열합니다.

실패 관용(fail-soft) 로그 패턴 (10줄)

검색 API 호출 실패 시 예외를 던지지 않고 빈 리스트를 반환하며 WARN 레벨로 “search failure” 로그를 남긴다.

ONNX 모델 로딩 실패 시 리랭커 백엔드를 embedding-model로 자동 전환하고 WARN 로그를 출력한다.

Naver API 키 누락이나 한도 초과 시 “naver provider disabled” 메시지를 INFO 레벨로 기록한다.

Pinecone 연결 실패 시 “Vector store unavailable” 로그 후 빈 RAG 결과를 반환한다.

SelfAsk 단계가 시간 한도를 초과하면 첫 번째 검색 결과만 사용하고 “self‑ask timeout” 로그를 남긴다.

EvidenceRepair가 수행되지 못하면 “evidence repair skipped” WARN 로그를 기록한다.

사용자 취소 요청 시 “cancelled by user” 로그를 남기고 작업을 즉시 중단한다.

실패 관용 로직은 사용자에게 오류를 노출하지 않고 기본 답변을 제공하며, 운영 시 WARN 이상의 로그를 모니터링해야 한다.

운영 점검 체크리스트 (16줄)

기동 시 LangChain4j purity OK 로그 확인 및 /actuator/health가 UP 상태인지 점검한다.

health/onnx_selftest.json의 passed 값과 ONNX 모델 SHA256 검증 결과를 확인한다.

MariaDB HikariCP 커넥션과 캐시 히트율, metrics 파일(metrics/samples.ndjson)의 업데이트 상태를 모니터링한다.

외부 API QPS 제한 준수와 search.keyword.selected-terms.cache-ttl-minutes를 통한 키워드 캐시 TTL 관리를 병행한다.

runRegistry 세션 수와 rag.snippet.ttl-days 설정 및 스니펫 TTL을 지속적으로 모니터링한다.

모델 라우터 승격(Mini→High) 및 각 핸들러의 처리·대기 시간을 Micrometer로 수집한다.

lms.debug.websearch.dump.curl을 일시적으로 켜서 외부 API 호출을 재현하고 디버그 로그를 분석한다.

JVM 메모리 및 GC 메트릭을 관찰하고 장애 발생 시 로그와 메트릭으로 원인을 좁혀가는 절차를 문서화한다.

ONNX 모델 업데이트 시 SHA256 검증과 버전 기록을 유지하고 리랭커 설정을 주기적으로 검토한다.

캐시 히트율과 Redis 쿨다운 서비스의 동작을 확인하여 과도한 호출을 방지하고 runRegistry 세션 만료 정책을 정기적으로 점검한다.

커버리지/리플레이 보고 열람 (8줄)

테스트 실행 후 coverage/jacoco-report/index.html을 브라우저로 열어 각 클래스의 커버리지를 확인합니다.

오프라인 리플레이 결과는 build/reports/offline-replay 폴더의 HTML 또는 CSV 파일로 제공됩니다.

테스트 실패나 지표 하락이 발생하면 관련 로그를 찾아 수정해야 합니다.

SonarQube와 통합되어 있다면 Jacoco XML 출력 (build/reports/jacoco/test/jacocoTestReport.xml)을 SonarQube 분석에 제출할 수 있습니다.

J. API 가이드

채팅 서비스에서 제공하는 HTTP 엔드포인트의 사용 방법을 설명합니다. 모든 요청은 /api/chat 경로를 기본으로 하며, JSON 페이로드를 주고받습니다.

/api/chat 요청/응답 예시 (14줄)

동기 호출은 POST /api/chat 엔드포인트를 사용합니다. 응답에는 모델명과 RAG 사용 여부가 헤더로 포함됩니다.

curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"서울 날씨 알려줘","attachmentIds":[],"useRag":true,"model":"gpt-mini"}'

# 응답 예
{
  "message": "현재 서울의 날씨는 맑고 기온은 25℃입니다.",
  "sessionId": 1,
  "modelUsed": "gpt-mini",
  "ragUsed": true
}

SSE 스트림 예시 (12줄)

스트리밍 응답을 받으려면 /api/chat/stream에 POST하고, Accept: text/event-stream 헤더를 사용합니다. SSE 스트림은 data: 프리픽스를 포함하는 이벤트를 연속적으로 전송합니다.

curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"최신 게임 출시 소식은?","sessionId":2,"useRag":true}' --no-buffer

# SSE 데이터는 다음과 같은 형식으로 전달됩니다:
data: {"type":"chunk","content":"최근 출시된 게임은 …"}
data: {"type":"chunk","content":"추가 정보 …"}
data: {"type":"end"}

첨부 업로드 → 채팅 흐름 (12줄)

파일을 업로드한 뒤 attachment ID를 사용하여 채팅을 수행하는 방법입니다.

# 파일 업로드
curl -X POST http://localhost:8080/api/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/path/to/report.pdf"

# 첨부 ID가 "att789"라고 가정하고 채팅 요청
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"보고서 요약해줘","attachmentIds":["att789"],"useRag":true}'


업로드 후에는 attachmentService.attachToSession이 호출되어 세션과 첨부 파일을 매핑합니다.

취소/세션 관리 예시 (12줄)

생성 중인 챗봇 응답을 취소하거나 세션 목록을 조회·삭제하는 엔드포인트입니다.

# 세션 취소
curl -X POST http://localhost:8080/api/chat/cancel?sessionId=1

# 세션 목록 조회
curl http://localhost:8080/api/chat/sessions

# 특정 세션 조회
curl http://localhost:8080/api/chat/sessions/1

# 세션 삭제 (관리자 권한 필요)
curl -X DELETE http://localhost:8080/api/chat/sessions/1

상태/디버그 게이트 설명 (10줄)

/api/chat/state – 현재 세션의 실행 상태를 반환합니다. running(Boolean), modelUsed(String), lastAssistant(Message), traceHtml(String) 필드를 포함합니다. debug=true를 지정하면 trace HTML을 포함시킬 수 있습니다.

/api/chat/sync – 동기식으로 채팅을 수행하는 엔드포인트입니다. 사용자가 SSE를 원하지 않는 경우 이 엔드포인트를 사용합니다.

X-Model-Used 헤더 – 실제 사용된 모델명을 나타냅니다. 래퍼명이나 별칭 대신 실제 모델을 기록해야 합니다.

X-RAG-Used 헤더 – RAG가 활성화된 경우 true를 반환합니다.

X-User 헤더 – 인증된 사용자의 이름을 반환합니다. 인증이 없는 경우 anonymousUser입니다.

Access-Control-Expose-Headers – 프런트엔드가 커스텀 헤더를 읽을 수 있도록 지정합니다.

SSE 스트림의 완료 이벤트는 {"type":"end"} 형식으로 제공되며, 클라이언트는 이를 수신하면 스트림을 닫아야 합니다.

API 버전 변경 시 /api/chat/v2 등의 새로운 경로로 추가해야 하며, 기존 엔드포인트는 하위 호환성을 유지합니다.

인증/인가는 Spring Security를 통해 처리되며, 권한 없는 사용자에게는 세션 삭제가 거부됩니다.

오류 발생 시 HTTP 상태 코드와 함께 오류 메시지가 JSON으로 반환됩니다.

K. 보안/키/비밀정보

이 절에서는 키와 비밀 정보 관리에 대한 정책을 정의합니다. 환경변수를 통한 비밀 주입을 금지하며, 키를 안전하게 저장·배포하는 방법을 안내합니다.

환경변수 금지 및 이유 (10줄)

보안 위험: 환경변수는 시스템 전체에서 조회 가능하며, 의도치 않은 노출 위험이 있습니다. 특히 컨테이너 이미지 또는 CI 로그에 노출될 수 있습니다.

일관성 부족: 개발, 테스트, 운영 환경에서 동일한 환경변수 구성이 어렵습니다. 파일 기반 설정은 버전 관리와 복제가 용이합니다.

자동화 충돌: Spring Boot는 application-*.yml 파일을 우선으로 사용하며, 환경변수가 이를 덮어쓸 수 있습니다. 의도치 않게 값이 변경될 수 있으므로 금지합니다.

테스트 재현성: 리플레이 테스트와 오프라인 평가에서 동일한 키를 사용해야 재현 가능성이 높아집니다. 환경변수는 실행마다 변경될 수 있습니다.

감사 추적: 파일 기반 키는 변경 이력을 Git과 Pull Request에서 추적할 수 있지만, 환경변수는 변경 기록이 남지 않습니다.

Vault 연동: 키가 필요한 경우 Vault나 Kubernetes Secret을 통해 파일을 마운트하는 방식이 권장됩니다. 이러한 솔루션은 환경변수 사용보다 안전합니다.

프롬프트 안전성: 키를 잘못 주입하면 프롬프트에 키가 포함될 위험이 있으며, 이는 LLM 프롬프트 공격의 표적이 됩니다.

법적 준수: 내부 정책 및 GDPR 규정에 따라 비밀 정보는 암호화된 저장소에 저장해야 합니다. ENV 변수는 이러한 요건을 충족하지 못합니다.

테스트 격리: CI 환경에서는 테스트 키가 하드코딩된 샘플 파일에서 로드되어야 하며, 환경변수는 비활성화됩니다.

STOP 가드: 환경변수 주입을 시도하면 빌드 또는 부트 단계에서 예외가 발생하도록 설정되어 있습니다.

마스킹 규칙 (8줄)

키를 README나 코드 예시에 포함할 때는 가운데 60~70 %를 별표(*)로 마스킹합니다.

예: 실제 키 표기

첫 4자와 마지막 6~8자를 제외하고 모두 숨기는 것이 일반적입니다.

비밀번호, 토큰, API 키 등 모든 비밀에 동일한 규칙을 적용합니다.

샘플 파일에는 실제 키를 포함하지 않고, 전부 별표 처리합니다.

마스킹된 값은 실행에 사용할 수 없으므로, 배포 환경에서 실제 키를 다시 입력해야 합니다.

로깅 시 키가 출력되지 않도록 mask-secrets 설정을 활성화합니다.

PR 검토자는 마스킹 규칙 준수를 확인해야 합니다.

공개 저장소 주의 (8줄)

비밀 키가 포함된 파일을 공개 저장소에 커밋하면 즉시 삭제하고 새 키를 발급받아야 합니다.

application-secrets.yml은 .gitignore에 포함하거나, 예시 파일만 업로드합니다.

이미지, PDF 등 첨부 파일에 비밀 정보가 포함될 수 있으므로 업로드 전에 내용을 확인합니다.

로그 파일을 첨부할 때 비밀 키가 마스킹되었는지 검토합니다.

CI 환경에서는 암호화된 변수나 시크릿 스토어를 사용하여 키를 주입합니다.

공개 PR 에서는 비밀 키를 노출하는 코드를 제출하지 않아야 하며, 필요 시 리뷰 요청 전 비공개 브랜치에서 검토합니다.

라이선스 또는 서드파티 어딘가에서 키를 요구하는 경우, 키 자체를 제공하지 말고 설명 링크만 제공하십시오.

키 노출이 의심될 경우 즉시 프로젝트 소유자에게 알리고, 모든 관련 키를 폐기 및 재발급합니다.

감사/교차검증 절차 (8줄)

PR 머지 전 리뷰어는 비밀 정보가 포함된 파일이 없는지 확인하고, 자동화 스크립트(./scripts/verify_after.sh)를 통해 키 누락, 중복 키, 금지 의존성 등을 검사한다.

정기적으로 git log와 PR 메시지를 검토하여 과거 커밋에 비밀이 포함되지 않았는지 확인하고, 운영 환경에서도 환경 변수가 비노출 상태인지 감시한다.

감사 결과는 내부 위키에 기록하며 발견된 문제에 대한 재발 방지 대책을 수립하고, 발견된 비밀 노출은 즉시 키를 폐기·재발급한다.

월 단위로 라이브 서비스의 모든 시크릿 설정을 검토하고 만료된 키를 삭제하며, 변경 내역을 릴리스 노트에 반영한다.

키 노출이 의심될 경우 즉시 프로젝트 소유자에게 알리고 모든 관련 키를 갱신한 후 영향을 받은 시스템을 업데이트한다.

감사를 통해 발견된 취약점과 개선 사항은 다음 릴리스에서 README와 코드에 반영하여 보안을 강화한다.

L. 트러블슈팅

일반적인 오류와 해결 방법을 정리했습니다. 문제가 발생하면 로그와 설정을 검토하고 아래 절차를 따르십시오.

YAML DuplicateKeyException (8줄)

원인: application.yml 또는 .properties 파일에서 동일한 키가 중복으로 선언되면 Spring Boot가 DuplicateKeyException을 발생시킵니다.

해결: 중복된 키를 하나로 합치거나 하위 섹션을 적절히 중첩합니다. 예를 들어 naver.search와 naver.search.web-top-k를 다른 위치에 선언하면 중복으로 간주될 수 있습니다.

검사: IDE나 YAML Lint를 사용하여 중복 키를 사전 검증합니다. Gradle 빌드가 시작되기 전에 정적 검사를 실행하는 것이 좋습니다.

예방: 설정을 모듈별 파일로 분리하고, 공통 설정을 별도 파일에 정의하여 충돌을 방지합니다.

Invalid API Key (8줄)

원인: application-secrets.yml에 입력한 키가 잘못되었거나 만료되었습니다. Naver/Brave API 호출이 401 또는 403 오류를 반환합니다.

해결: 키 문자열이 정확한지, 필수 접두사(BSAPI, gsk_ 등)를 포함하는지 확인합니다. 키가 노출되었으면 새 키로 교체해야 합니다.

검사: 개발 모드에서 lms.debug.websearch.dump.curl=true를 설정하고 실패 응답을 확인하십시오.

예방: 키의 유효 기간을 주기적으로 확인하고, 만료 전에 갱신합니다. 운영 환경에서 키 회전 정책을 설정합니다.

ONNX 모델 경로/SHA 검증 (5줄)

원인: ONNX 리랭커를 활성화했지만 model-path에 지정된 파일이 존재하지 않거나 손상되었습니다.

해결: classpath:/models/your-cross-encoder.onnx 경로에 모델 파일을 추가하고, SHA256 해시가 배포 문서와 일치하는지 확인합니다.

검사: health/onnx_selftest.json의 passed가 false이면 모델 로딩에 실패했음을 의미합니다.

예방: 모델을 업데이트할 때마다 해시와 버전을 기록하여 무결성을 검증합니다. CD 파이프라인에서 해시 검사를 자동화합니다.

비활성화: 임시로 리랭커를 비활성화하려면 abandonware.reranker.backend=embedding-model로 설정합니다.

M. 성능/비용/품질

LLM과 웹 검색 호출은 비용과 시간이 소요되므로, 다음 팁을 따라 효율을 최적화하십시오.

토큰/요금 상한 팁 (5줄)

generationParams.targetTokenBudgetOut 값으로 단일 응답의 토큰 한도를 설정하고, 긴 대화는 요약을 통해 히스토리 길이를 줄여 토큰 사용량을 절감한다.

불필요한 RAG 검색을 피하기 위해 useRag 플래그를 false로 설정하거나 ragEnabled를 조건부로 사용하며, 긴 문서에서는 첨부 파일에서 필요한 부분만 추출해 localDocs에 전달한다.

온프레미스 모델을 사용하면 비용을 줄일 수 있으나 품질과 속도를 고려해야 하며, 토큰 예산에 따라 적절한 모델을 선택한다.

토큰 사용량을 지속적으로 모니터링하고, 응답 길이가 예산을 초과할 경우 프롬프트 클리퍼를 적용한다.

검색 상한/타임박싱 (5줄)

SelfAsk와 Analyze 단계에서 max-api-calls-per-query 값을 조정해 최대 호출 수와 per-request-timeout-ms 값을 함께 제한하여 느린 API 응답을 방지한다.

검색 결과 top-k 값을 줄여 네트워크 호출과 리랭크 비용을 감소시키되, 너무 낮게 설정하면 정보 부족이 발생할 수 있음을 유의한다.

search.budget.per-page-ms를 이용해 페이지 본문 추출 시간을 제한하고, 다수의 외부 API 호출을 병렬로 실행할 경우 CPU 코어 수에 맞춰 스레드 풀 크기를 조정한다.

검색을 조건부로 스킵하거나 useRag 플래그를 false로 설정하여 불필요한 외부 호출을 줄인다.

캐시/재시도 힌트 (3줄)

Caffeine 캐시의 TTL을 적절히 설정하고, cache.refresh-after-write를 사용하여 백그라운드 갱신을 수행하십시오.

외부 API 호출에는 재시도 로직과 백오프 전략을 적용하여 일시적 오류를 완화합니다.

검색 쿼리를 정규화하여 동일한 질의가 캐시에 적중하도록 합니다.

N. 기여/브랜치/PR 가이드

새로운 기능이나 버그 수정에 기여하려면 아래 가이드와 체크리스트를 따라 주세요.

브랜치 전략 (6줄)

main/master: 안정적인 배포 버전을 유지하며, 직접 커밋은 금지됩니다.

develop: 다음 릴리스를 위한 통합 브랜치로 사용되며, 기능 브랜치는 여기에 병합됩니다.

feature/xxx: 새로운 기능은 feature/<이름> 브랜치에서 개발합니다.

bugfix/xxx: 버그 수정은 bugfix/<이름> 브랜치에서 수행합니다.

release/ 브랜치: 릴리스 준비 단계에서 사용되며 버전 변경과 문서 업데이트를 포함합니다.

hotfix/ 브랜치: 긴급 수정이 필요한 경우 main/master에서 파생하여 적용 후 다시 main/master와 develop으로 병합합니다.

PR 체크리스트 (10줄)

Gradle 빌드(./gradlew clean build)와 ./gradlew check(purity, placeholder, offline replay)가 성공하는지 확인한다.

비밀 키 파일(application-secrets.yml)이 커밋되지 않았고, 변경 사항이 없는지 검토한다.

PromptBuilder 규칙을 준수하며 문자열 직접 결합을 하지 않았고, 새로운 핸들러 추가 시 RetrieverChainConfig에 연결했는지 확인한다.

테스트 케이스를 추가하거나 수정하여 새 기능이 검증되며, 변경된 설정 값이 README와 샘플 파일에 반영되었는지 검토한다.

코드 스타일(Google Java Style, 2‑space 인덴트)과 자바 17 기능 사용 규칙을 지켰는지 확인한다.

PR 설명에 변경 내용, 동기, 테스트 방법을 명확히 작성하고 리뷰어 피드백을 반영했는지 검토한다.

Gradle resolutionStrategy가 LangChain4j 1.0.1을 강제하고 forbidden dependency가 없는지 확인한다.

LLM API 키나 시크릿 값이 로그에 노출되지 않도록 디버그 옵션을 조정했는지 점검한다.

코드 스타일/라이너 규칙 (5줄)

자바 코드: Google Java Style을 기반으로 하며, 2‑space 인덴트를 사용합니다.

Kotlin/Scala 코드가 없는 프로젝트입니다. Kotlin 파일을 추가할 경우 별도의 스타일 가이드가 필요합니다.

줄 길이: 한 줄에 120자를 넘지 않도록 합니다. 긴 문자열은 줄바꿈 또는 text block을 사용합니다.

주석: 클래스와 메서드 수준의 JavaDoc을 작성하고, 한글 설명 뒤 괄호 안에 영문 용어를 병기합니다.

테스트 명명: 테스트 메서드는 명확한 Given/When/Then 서술형 이름을 사용합니다.

O. 라이선스/저작권/문서 메타

본 프로젝트는 아직 확정된 라이선스가 없으며, 아래 문구는 임시 안내용입니다. 실제 사용 전에는 라이선스를 지정해야 합니다.

라이선스 (5줄)

이 저장소의 소스 코드와 문서는 “모든 권리 보유” 상태입니다. 여러분은 내부 평가 및 학습 목적에 한해 코드를 열람하고 실행할 수 있습니다. 상업적 이용, 재배포 및 수정은 소유자의 서면 허가 없이 금지됩니다. 향후 Apache 2.0 또는 MIT 라이선스로 전환될 수 있습니다.

저작권 및 서드파티 고지 (5줄)

프로젝트의 저작권은 2025 AbandonWareAI 팀에 있습니다. 소스 트리에 포함된 3rd‑party 라이브러리는 각 라이브러리의 LICENSE 파일에 따라 사용됩니다. node_modules/@fortawesome 디렉터리의 아이콘과 pptxgenjs 라이브러리는 해당 프로젝트의 라이선스를 따르며, 상업적 사용 시 각자의 조건을 검토해야 합니다. ONNX 모델과 외부 API의 응답 데이터는 원 저작권자의 소유입니다.

문서 버전/날짜/작성자 (3줄)

문서 버전: v0.1 (초안)

작성일: 2025‑08‑28 (Asia/Seoul)

작성자: GPT Pro 에이전트
