AbandonWare AI RAG Chatbot

“ImSQL README 스타일로 투영한 운영용 README”

Java 17 · Spring Boot 3.4.x · LangChain4j 1.0.1(단일선 고정)
Naver/Brave/Tavily + Vector DB 결합 RAG · CoR(Self‑Ask → Analyze → Web → Vector)
환경변수 기반 비밀 주입 금지 · 버전 순도 가드 · 프롬프트 문자열 연결 금지

한눈에 보기 (TL;DR)

CoR 체인: SelfAsk → Analyze → Web → VectorDb (+ SearchCostGuard, EvidenceRepair).

PromptBuilder 규칙: 항상 PromptBuilder.build(PromptContext) 사용. 문자열 직접 concat 금지.

버전 순도: dev.langchain4j:* 1.0.1 고정, com.theokanning / openai-service 금지.

비밀/키: 환경변수로 주입 금지. src/main/resources/application-secrets.yml 파일 기반만 허용.

리랭커: RRF + 선택적 ONNX Cross‑Encoder(onnxruntime).

세션 격리: rag.safety.enforceSid=true, 세션/증거 TTL 관리.

모델 라우팅(MoE): mini ↔ high 자동 전환(토큰/복잡도/불확실성 기반).

목차

필수 사전 설치 안내

빠른 시작 (Build/Run)

시작 전 cURL 테스트

데모: 업로드 → 채팅 → 스트리밍

API 개발 문서 / Postman

핵심 기능(요약)

필수 설정(버전 순도/프로퍼티/시크릿)

프로필/포트/운영 팁

운영 헬스/메트릭

파일/디렉터리 설명

개발 환경 & 사용 라이브러리

TODO

트러블슈팅

보안/비밀/감사

검증 태스크

라이선스 / 문서 메타

개발자/문의

필수 사전 설치 안내

▶ JDK 17 (필수)

java -version으로 17 확인.

▶ Gradle Wrapper

저장소에 포함된 ./gradlew 사용(별도 설치 불필요).

▶ 네트워크

외부 검색/모델 호출 위해 인터넷 필요.

▶ 포트

기본 8080 (변경 시 server.port).

▶ 시크릿/키(필수)

src/main/resources/application-secrets.yml 작성(아래 샘플 참고).

환경변수로 키 주입 금지.

▶ 선택: ONNX 리랭커

onnxruntime 의존성 활성 + 모델 파일(classpath:/models/your-cross-encoder.onnx) 배치.

빠른 시작 (Build/Run)
# 1) 클론
git clone https://github.com/UnlimitedAbandonWare/AbandonWareAi.git
cd AbandonWareAi

# 2) 시크릿 파일 생성(샘플은 아래 참고)
#    src/main/resources/application-secrets.yml

# 3) 빌드(순도 검증 + 테스트 포함)
./gradlew clean build

# 4) 실행(ultra 프로필 예시)
./gradlew bootRun -Dspring.profiles.active=ultra
# 또는
java -jar build/libs/*.jar --spring.profiles.active=ultra

시작 전 cURL 테스트
# 헬스체크 (Spring Boot Actuator)
curl -s http://localhost:8080/actuator/health
# => {"status":"UP"}

# 런타임 상태(트레이스 포함)
curl -s "http://localhost:8080/api/chat/state?debug=true"

# 세션 목록
curl -s http://localhost:8080/api/chat/sessions


예시 응답(발췌):

{
  "running": false,
  "modelUsed": "gpt-5-mini",
  "ragUsed": true,
  "traceHtml": "<html>...</html>"
}

데모: 업로드 → 채팅 → 스트리밍

1) 첨부 업로드

curl -X POST http://localhost:8080/api/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/path/to/sample.pdf"


응답 예:

{ "attachmentIds": ["att123"], "message": "uploaded" }


2) 동기 채팅

curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"안녕","attachmentIds":["att123"],"useRag":true,"model":"gpt-mini"}'


3) SSE 스트리밍

curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"제조업 혁신 사례 알려줘","sessionId":1,"useRag":true}' --no-buffer


스트림 예:

data: {"type":"chunk","content":"최근 제조업 R&D 사례는..."}
data: {"type":"end"}


(선택) 취소

curl -X POST "http://localhost:8080/api/chat/cancel?sessionId=1"

API 개발 문서 / Postman

Postman 컬렉션: POSTMAN_COLLECTION.json (루트에 포함)

base_url → http://localhost:8080

주요 엔드포인트: /api/chat, /api/chat/stream, /api/upload, /api/chat/state, /api/chat/sessions, /api/chat/cancel

스웨거 도입 시 /swagger-ui/index.html 경로로 접근 (선택).

핵심 기능(요약)
1) CoR 검색 파이프라인

SelfAsk → Analyze → Web → VectorDb 순으로 실행.

SearchCostGuard: 예상 토큰 초과 시 검색 축소/스킵.

EvidenceRepair: 저품질/오염 증거 교정·교체.

2) PromptBuilder 규칙

프롬프트는 반드시 PromptBuilder.build(PromptContext)로 생성.

섹션 순서 고정: ATTACH → RAG → WEB → HISTORY → MEMORY.

MUST_INCLUDE(1~4 키워드) 자동 추출, 위치정보 제공 시 ### LOCATION CONTEXT 추가.

문자열 concat 금지, 널 검사/토큰 클리핑 내장.

3) 세션 격리 & 메모리

rag.safety.enforceSid=true 로 세션 간 증거 혼용 방지.

MemoryReinforcementService가 장기메모리 요약/주입, TTL 관리.

4) 리랭킹

기본 RRF 융합 + ONNX Cross‑Encoder(선택) 또는 임베딩 리랭커.

상위 Top‑K(≈24~32)만 최종 PromptContext에 주입.

5) 버전 순도 & 금지 의존성

dev.langchain4j:* 1.0.1 단일선 고정.

com.theokanning / openai-service 금지, 발견 즉시 빌드 실패.

6) 모델 라우팅(MoE)

mini(속도) ↔ high(정확) 자동 전환(토큰/복잡도/불확실성 기준).

헤더 다운그레이드 차단: router.allow-header-override=false.

필수 설정(버전 순도/프로퍼티/시크릿)
▶ Gradle: LangChain4j 1.0.1 단일화 & 금지 의존성
// build.gradle (발췌)
configurations.all {
    resolutionStrategy.eachDependency { d ->
        if (d.requested.group == "dev.langchain4j") {
            useVersion("1.0.1")
        }
        if (d.requested.group == "com.theokanning" ||
            (d.requested.name != null && d.requested.name.contains("openai-service"))) {
            throw new GradleException("Forbidden dependency detected: ${d.requested}")
        }
    }
}
dependencyManagement {
    imports {
        mavenBom "dev.langchain4j:langchain4j-bom:1.0.1"
    }
}
tasks.named('check') { dependsOn("checkLangchain4jVersionPurity") } // 커스텀 태스크 권장

▶ application.properties (기본값 정렬 — 추가/치환)
# 모델 라우팅 (5계열로 동기화)
langchain4j.openai.chat-model.model-name=gpt-5-mini
openai.model.moe=gpt-5-chat-latest

router.moe.mini=gpt-5-mini
router.moe.high=gpt-5-chat-latest
router.moe.threshold=0.62
router.moe.margin=0.08

query.correction.model=gpt-5-mini
ner.model=gpt-5-mini
fallback.model=gpt-5-mini
openai.api.model.default=gpt-5-mini

# 헤더로 모델 강등 금지
router.allow-header-override=false


주의: 기존 gpt-4o/gpt-4o-mini 등 모두 제거. application.yml에도 동일 키 동기화.

▶ 코드 변경(핵심 포인트)

ModelConfig.java

// Before (예)
@Value("${router.moe.mini:gpt-4o-mini}") private String miniModelName;
@Value("${router.moe.high:gpt-4o}")     private String highModelName;

// After
@Value("${router.moe.mini:gpt-5-mini}")        private String miniModelName;
@Value("${router.moe.high:gpt-5-chat-latest}") private String highModelName;


ChatService.java

하드코딩된 모델명 제거 → modelRouter.resolveModelName(..) 결과만 사용.

프롬프트 문자열 결합 금지 → PromptBuilder.build(PromptContext) 강제.

ModelRouter 단일 주입: com.example.lms.service.routing.ModelRouter.

ModelRouter 단일화

com.example.lms.service.routing.ModelRouter 채택, 과거 도메인/어댑터는 삭제 또는 위임.

▶ application-secrets.yml (샘플 — 가운데 마스킹 필수)
spring:
  config:
    activate:
      on-profile: secrets

search:
  brave:
    enabled: true
    api-key: "BSAPI******MASKED******1234"

openai:
  api:
    key: "sk-****MASKED****abcd"

upstash:
  vector:
    rest-url: "https://<YOUR_UPSTASH_VECTOR_ENDPOINT>"
    api-key: "upstash-****MASKED****xyz"
    namespace: "aw-default"
    read-only: true


실제 키는 커밋 금지. 운영은 파일 마운트(Secret/Vault) 방식 권장. ENV 주입 금지.

프로필/포트/운영 팁

프로필: ultra(운영/디버그 풍부), dev(로컬), secrets(시크릿 주입), test.

다중 활성 가능: --spring.profiles.active=dev,secrets (항상 secrets는 마지막)

주요 튜닝 키(발췌):

abandonware.reranker.backend = onnx-runtime | embedding-model | noop

rag.search.top-k (기본 10) / rag.precision.max-aggregate-chars (기본 35000)

rag.guard.min-evidence-count / rag.guard.min-evidence-from-distinct-sources

router.moe.tokens-threshold / router.moe.complexity-threshold / router.moe.uncertainty-threshold

lms.debug.websearch.dump.curl=true (디버깅 시 외부 호출 cURL로 로깅)

운영 헬스/메트릭

Actuator: /actuator/health, /actuator/info, /actuator/env

ONNX Self‑Test: health/onnx_selftest.json 로딩, 실패 시 임베딩 리랭커로 폴백.

메트릭 샘플: metrics/samples.ndjson (Loki/Prometheus로 수집 가능).

로그 레벨 팁:

검색 실패: WARN "search failure"

네이버 키 누락: INFO "naver provider disabled"

벡터 불가: WARN "Vector store unavailable"

모형 전환: INFO "MOE route: mini→high"

파일/디렉터리 설명
경로/파일	설명
src/main/java/com/example/lms/api/ChatApiController.java	채팅/스트림/세션 관리 REST API
src/main/java/com/example/lms/config/RetrieverChainConfig.java	SelfAsk→Analyze→(CostGuard)→Web→Vector 체인 구성
src/main/java/com/example/lms/service/ChatService.java	비즈니스 로직·모델 호출·PromptContext 조립
src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java	Self‑Ask 검색/키워드 확장
src/main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java	질의 분석/도메인 감지
src/main/java/com/example/lms/service/rag/WebSearchRetriever.java	Naver/Brave/Tavily 통합
src/main/java/com/example/lms/service/rag/LangChainRAGService.java	Vector DB 조회·RAG 캡슐화
src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java	ONNX Cross‑Encoder 리랭커
src/main/java/com/example/lms/service/rag/handler/SearchCostGuardHandler.java	토큰 비용 가드
src/main/java/com/example/lms/service/rag/handler/EvidenceRepairHandler.java	증거 보수/교정
src/main/java/com/example/lms/prompt/PromptBuilder.java	프롬프트 생성(섹션/클리핑/머스트 포함)
src/main/java/com/example/lms/prompt/PromptContext.java	프롬프트 컨텍스트 DTO(record)
src/main/resources/application.yml	RAG/검색/라우팅/세션/리랭커 등 기본 설정
src/main/resources/application-secrets.yml	비밀 키 저장소(샘플만 커밋)
개발 환경 & 사용 라이브러리

개발 환경

OS: Linux/macOS(주), Windows 지원

빌드: Gradle(Wrapper)

Java: 17

Spring Boot: 3.4.x

DB: MariaDB(세션/이력), VectorDB(Pinecone/Upstash Vector 등)

주요 라이브러리

Spring Boot (Web, Actuator), Micrometer

LangChain4j 1.0.1 (BOM/core/starter/openai)

onnxruntime(선택), Caffeine Cache

Vector Store Client(Pinecone/Upstash)

Jackson, Netty/HTTP 클라이언트

TODO

📁 데이터/DB

Vector 2차 인덱싱 파이프라인(증분 업데이트)

세션/메모리 릴레이션 최적화 및 TTL 모니터링

🧠 RAG & 리랭크

ONNX 모델 교체 벤치(Top‑K/Latency/NDCG 비교 리포트 자동화)

도메인 부스팅/필터 정책 UI화

🧩 웹 검색

도메인 허용/차단 리스트 런타임 갱신 API

Wiki Provider 한국어/영문 스위칭 자동화

🔐 보안

파일 마운트 시크릿 검증 + 키 마스킹 유닛테스트

IP 화이트리스트/레이트리밋 게이트 추가

🧪 테스트/품질

오프라인 리플레이 데이터셋 확대

PromptGuardTest 케이스 보강(섹션 순서/클리핑 한계)

트러블슈팅

키 파일 누락/포트 충돌 → application-secrets.yml 존재 확인, server.port 조정.

혼합 LangChain4j 버전 → ./gradlew check 실패 시 build/reports/langchain4j-conflicts.txt 확인 후 정리.

ONNX 모델 없음 → abandonware.reranker.backend=embedding-model로 임시 전환 또는 모델 배치.

YAML DuplicateKeyException → 중복 키 제거/섹션 재배치.

Invalid API Key(401/403) → 접두사·형식 확인(가운데 마스킹 규칙 유지), 키 재발급.

보안/비밀/감사

환경변수 기반 주입 금지: System.getenv 호출 금지. 파일 기반만 허용.

마스킹 규칙: 앞 4 + 뒤 6~8자만 노출, 나머지 *.

로그 마스킹: lms.debug.mask-secrets=true 권장.

Git 정책: 실제 키 커밋 금지, application-secrets.sample.yml 만 공개.

노출 의심 시: 즉시 키 폐기·재발급, 영향 범위 점검/감사 기록.

검증 태스크
# 단위/통합 + 순도/플레이스홀더/오프라인 리플레이
./gradlew check

# 플레이스홀더 금지 검사 (..., // TODO 등)
./gradlew failOnPlaceholders

# 오프라인 리플레이 (예시 로그로 NDCG/MRR 측정)
./gradlew offlineReplayCheck -PreplayLog=logs/sample.log -Preplay.ndcgMin=0.25 -Preplay.mrrMin=0.25

# 의존성/클래스패스 리포트
./gradlew emitDependencyReport   # -> build/reports/dependency-report.txt
./gradlew emitClasspathJars      # -> build/reports/classpath-jars.txt


테스트 수락 기준(요지)

RouterSmokeTest: mini ↔ high 전환(임계/마진)

SearchCostGuardTest: 12k±500 토큰 경계 우회 on/off

RouteSignalVisibilityTest: new RouteSignal(...) 외부 패키지에서 생성 가능

PromptGuardTest: ChatService 내 프롬프트 concat 0건

회귀: “증거 있음 → ‘정보 없음’” 0건

버전 순도: classpath 기준 dev.langchain4j 1.0.1 단일선

라이선스 / 문서 메타

라이선스(임시): “모든 권리 보유(ALL RIGHTS RESERVED)”. 내부 평가·학습 목적의 열람/실행 가능. 상업적 이용/재배포/수정은 서면 허가 필요.
(향후 Apache-2.0 또는 MIT 전환 가능)

문서 버전: v0.1 (초안)

작성일: 2025‑08‑28 (Asia/Seoul)

저작권: © 2025 AbandonWareAI Team

개발자/문의

AbandonWare AI RAG Chatbot Maintainers

기술 문의: 프로젝트 이슈 트래커 사용 권장

보안 보고: 긴급 키 노출/취약점 신고 시 저장소 소유자에게 즉시 연락

부록 A — 운영 체크리스트 (요약)

 기동 시 LangChain4j purity OK 로그 확인, /actuator/health == UP

 health/onnx_selftest.json 결과 확인(실패 시 폴백 정상 여부)

 MariaDB/Vector 연결 상태 및 캐시 히트율 모니터링

 router.allow-header-override=false 적용 확인(헤더 강등 차단)

 rag.safety.enforceSid=true(세션 격리)

 외부 API QPS/Retry/백오프 설정 준수, lms.debug.websearch.dump.curl 일시적 활용

 모델 라우팅/핸들러 시간 메트릭 수집(Micrometer)

 키 파일 Git 미포함, 샘플만 공개 유지

부록 B — 프롬프트/컨텍스트 규약(요약)

순서: ATTACH → RAG → WEB → HISTORY → MEMORY

위치 제공 시 ### LOCATION CONTEXT 추가

MUST_INCLUDE 1~4 키워드 자동 삽입

토큰 예산 초과 시 자동 축약/다운그레이드(타깃 예산: generationParams.targetTokenBudgetOut)

문자열 concat 금지 / 널 체크 필수 / 섹션 동적 삽입 금지
