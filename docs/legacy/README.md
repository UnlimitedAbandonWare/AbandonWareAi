AbandonWareAI LMS - 하이브리드 RAG 시스템

이 리포지토리는 AbandonWareAI 프로젝트의 핵심 서버 소스 코드입니다. 교육용 대화형 AI를 위한 하이브리드 검색-생성(RAG) 엔진, 체인 오브 리스폰서빌리티(CoR), 전문가 라우팅(MOE), 위치 서비스 등을 제공하는 프로덕션용 백엔드입니다.
프로젝트는 Java 17, Spring Boot 3.x, Gradle, LangChain4j 1.0.1에 기반하며, 한국어를 지원하도록 특별히 최적화되었습니다.

TL;DR

한 줄 요약: 하이브리드 RAG 검색과 체계적인 책임 체인, 그리고 MOE 라우팅을 적용한 고품질 챗봇 플랫폼입니다.
이 README는 실제 소스 코드를 기반으로 프로덕션 운영팀과 개발팀이 즉시 사용할 수 있도록 1000줄에 걸쳐 모든 기능을 상세히 설명합니다.

Pre-flight — Version Purity (STOP if mixed)

본 프로젝트에서 가장 중요한 게이트는 LangChain4j 버전 순도 검사입니다. 모든 dev.langchain4j:* 모듈이 1.0.1로 고정되어야 하며, 혼합 버전(예: 0.2.x)이 발견되면 빌드 및 배포를 즉시 중단해야 합니다.
다음 명령으로 의존성을 확인할 수 있습니다:

./gradlew dependencies -q | grep dev.langchain4j


모든 항목이 1.0.1을 가리키면 순도 검사를 통과한 것입니다. 만약 다른 버전이 나타난다면, 시스템은 자동으로 실패 상태로 전환하고 아래 세 가지 산출물만 생성해야 합니다.

build/reports/langchain4j-conflicts.txt – 혼합 버전 목록을 포함한 보고서

dependency-graph.txt – 간단한 의존성 그래프

gradle-fix.txt – BOM 또는 constraints를 이용한 수정 제안
순도 체크는 Gradle 태스크 checkLangchain4jVersionPurity에 의해 강제됩니다. classes 태스크가 실행되기 전에 순도 검사가 수행되므로, 버전이 섞여 있으면 컴파일조차 진행되지 않습니다.
이 README를 포함한 문서 생성은 순도 검사에 통과한 경우에만 수행됩니다. 혼합 버전이 발견되었다면 README를 수정하거나 추가하지 않습니다.

Architecture Overview
CoR 흐름 및 엔트리 포인트

이 시스템의 핵심은 Chain of Responsibility(CoR) 패턴입니다. 각 핸들러는 질의 처리 파이프라인의 한 단계를 담당하며, 실패하더라도 다음 단계로 넘어가 전체적인 부분 성공을 보장합니다. 조기 종료는 금지되어 있습니다.
엔트리 포인트는 HybridRetriever 클래스로, 이 클래스는 여러 핸들러를 연결하여 하이브리드 RAG 검색을 수행합니다. 기본 핸들러 체인은 다음과 같습니다:

HybridRetriever
  └─ SelfAskHandler
      └─ AnalyzeHandler (Query Hygiene)
          └─ WebSearchHandler
              └─ VectorDbHandler


각 단계는 질의의 성격에 따라 실행 여부가 달라집니다. SelfAskHandler는 질문이 모호할 때 추가 질문을 생성하고, AnalyzeHandler는 질의 위생을 검사합니다. WebSearchHandler는 웹 문서를 가져오며 VectorDbHandler는 벡터 데이터베이스에서 세그먼트를 검색합니다.

Fail-soft 정책과 핸들러 확장

본 프로젝트는 fail-soft를 원칙으로 합니다. 특정 핸들러가 실패하거나 결과가 없어도 이후 핸들러들이 계속 실행되며, 가능한 한 많은 정보를 누적합니다. 부분 성공을 통해 사용자에게 의미 있는 응답을 제공하되, 중간에 조기 반환하지 않습니다.
새로운 전략이나 데이터 소스를 도입하려면 com.example.lms.service.rag.handler 패키지에 새 핸들러 클래스를 추가하고, DefaultRetrievalHandlerChain 또는 DynamicRetrievalHandlerChain에 적절히 연결해야 합니다. 핸들러는 RetrievalHandler 인터페이스를 구현하며, handle 메서드에서 accumulator에 결과를 추가한 후 true를 반환하여 체인을 계속 진행합니다.

Retrieval 핸들러 목록

AbstractRetrievalHandler – AbstractRetrievalHandler 핸들러는 고유한 책임을 갖고 있습니다.

AnalyzeHandler – AnalyzeHandler 핸들러는 고유한 책임을 갖고 있습니다.

DefaultRetrievalHandlerChain – DefaultRetrievalHandlerChain 핸들러는 고유한 책임을 갖고 있습니다.

DynamicRetrievalHandlerChain – DynamicRetrievalHandlerChain 핸들러는 고유한 책임을 갖고 있습니다.

EntityDisambiguationHandler – EntityDisambiguationHandler 핸들러는 고유한 책임을 갖고 있습니다.

EvidenceRepairHandler – EvidenceRepairHandler 핸들러는 고유한 책임을 갖고 있습니다.

FileHandler – FileHandler 핸들러는 고유한 책임을 갖고 있습니다.

KnowledgeGraphHandler – KnowledgeGraphHandler 핸들러는 고유한 책임을 갖고 있습니다.

LocationAnswerHandler – LocationAnswerHandler 핸들러는 고유한 책임을 갖고 있습니다.

LocationAwareHandler – LocationAwareHandler 핸들러는 고유한 책임을 갖고 있습니다.

MemoryHandler – MemoryHandler 핸들러는 고유한 책임을 갖고 있습니다.

MemoryWriteInterceptor – MemoryWriteInterceptor 핸들러는 고유한 책임을 갖고 있습니다.

PairingGuardHandler – PairingGuardHandler 핸들러는 고유한 책임을 갖고 있습니다.

QueryRouteHandler – QueryRouteHandler 핸들러는 고유한 책임을 갖고 있습니다.

RetrievalHandler – RetrievalHandler 핸들러는 고유한 책임을 갖고 있습니다.

SelfAskHandler – SelfAskHandler 핸들러는 고유한 책임을 갖고 있습니다.

VectorDbHandler – VectorDbHandler 핸들러는 고유한 책임을 갖고 있습니다.

WebSearchHandler – WebSearchHandler 핸들러는 고유한 책임을 갖고 있습니다.
위 목록은 실제 com.example.lms.service.rag.handler 패키지에 포함된 클래스 명칭이며, 새로운 기능을 추가할 때는 이 목록을 참고하여 체인에 삽입합니다.

기본 체인 vs. 동적 체인

DefaultRetrievalHandlerChain은 고정된 순서로 핸들러를 호출합니다. 반면 DynamicRetrievalHandlerChain은 RetrievalOrderService를 통해 질의 유형을 분석하고 웹 검색, 벡터 검색, 지식 그래프 검색의 순서를 동적으로 결정합니다. 테스트 클래스 DynamicRetrievalHandlerChainOrderTest는 동적 체인의 순서가 설정 값에 따라 올바르게 결정되는지 검증합니다.

Reciprocal Rank Fusion(RRF)와 재랭킹

핸들러들이 반환한 결과는 ReciprocalRankFuser를 통해 Reciprocal Rank Fusion(RRF) 알고리즘으로 합쳐집니다. RRF는 여러 소스의 랭크를 조합하여 전반적인 순위를 계산합니다. 기본 공식은 다음과 같습니다:

각 소스에서의 순위를 rank_i라고 할 때, 점수는 Σ 1 / (k + rank_i)로 계산합니다.

k 값은 설정(ranking.rrf.k)을 통해 조정할 수 있으며 기본값은 50입니다.
합성된 결과는 선택적으로 Cross-Encoder Re-ranking 과정을 거쳐 재정렬됩니다. abandonware.reranker.backend 속성을 통해 아래 중 하나를 선택할 수 있습니다:

onnx-runtime: 로컬 ONNX 런타임을 사용하여 크로스 인코더 모델로 재랭킹

embedding-model: 임베딩 기반 크로스 인코더(기본값)

noop: 재랭킹을 비활성화

Routing (MOE)
ModelRouterCore 및 Escalation 로직

ModelRouterCore는 사용자의 질문과 현재 세션 상태를 기반으로 Mixture-of-Experts(MOE) 라우팅을 수행합니다. 기본 모델(gpt-5-mini)과 고품질 모델(gpt-5-chat-latest) 사이를 동적으로 선택합니다.
라우팅 결정은 MoeRoutingProps에서 정의된 임계값에 의해 좌우됩니다:

tokensThreshold: 질문의 토큰 수가 1200을 초과하면 승격됩니다.

complexityThreshold: 질의 복잡도 점수(0~1)가 0.55를 넘으면 승격됩니다.

uncertaintyThreshold: 불확실성 점수(0~1)가 0.40을 넘으면 승격됩니다.

webEvidenceThreshold: 웹 증거 강도(0~1)가 0.60을 넘으면 승격됩니다.

escalateOnRigidTemp: 강제 온도 조절을 사용했을 때 MOE로 승격할지 여부. 기본값은 true입니다.
또한 router.moe.learning-intents 속성을 통해 학습 의도가 설정되어 있으면 자동으로 MOE로 승격됩니다. 예: CURATION, RULE_INDUCTION, KB_UPDATE, ANALYSIS 등.

모델 및 프로퍼티 키

모델 식별자는 ModelProperties에서 정의됩니다. 기본 모델은 gpt-5-mini, MOE 모델은 gpt-5-chat-latest입니다. 운영 환경에서는 openai.chat.model.a-default와 openai.chat.model.moe 속성을 통해 다른 모델로 교체할 수 있습니다.
아래 표는 주요 라우팅 관련 프로퍼티와 간단한 의미를 요약한 것입니다. 자세한 설명은 본문을 참고하십시오:

키	의미
router.moe.tokens-threshold	토큰 임계값
router.moe.complexity-threshold	복잡도 임계값
router.moe.uncertainty-threshold	불확실성 임계값
router.moe.web-evidence-threshold	웹 증거 임계값
router.moe.escalate-on-rigid-temp	강제 온도 승격 여부
router.moe.learning-intents	MOE 학습 의도
openai.chat.model.a-default	기본 모델 이름
openai.chat.model.moe	MOE 모델 이름
abandonware.reranker.backend	재랭커 백엔드
ranking.rrf.k	RRF 융합 k 값
ranking.rerank.ce.topK	크로스 엔코더 상위 K
rag.search.top-k	검색 top-k

위 프로퍼티들은 application.yml 또는 application.properties에서 정의되며 필요에 따라 오버라이드할 수 있습니다.

Prompting Policy
중앙화된 프롬프트 구성과 금지 규칙

이 프로젝트는 프롬프트 중앙화를 핵심 원칙으로 합니다. 모든 프롬프트는 PromptBuilder.build(PromptContext)와 PromptBuilder.buildInstructions(PromptContext)를 통해 생성되며, 서비스 로직에서 문자열을 직접 이어붙이는 행위는 금지됩니다.
ChatService 또는 다른 서비스에서 "\n" 문자열이나 + 연산자로 프롬프트를 조립하는 흔적이 발견되면 정적 검사에 의해 실패합니다. 이를 방지하기 위해 다음 규칙을 따르십시오:

질문과 시스템 인스트럭션, 웹 증거, 벡터 RAG, 긴 기억, 위치 정보 등 모든 컨텍스트는 PromptContext 레코드에 필드로 채워 넣습니다.

PromptBuilder는 각 섹션을 ### WEB EVIDENCE, ### VECTOR RAG, ### LONG-TERM MEMORY, ### HISTORY, ### UPLOADED FILE CONTEXT, ### PREVIOUS_ANSWER, ### DRAFT_ANSWER, ### LOCATION CONTEXT, ### MUST_INCLUDE 와 같이 레이블을 붙여 포맷합니다.

페르소나(튜터, 분석자, 브레인스토머 등)는 abandonware.persona.* 속성을 통해 구성되며, 지시 문구는 buildInstructions에서 자동 삽입됩니다.

PromptBuilder 예시 코드

아래는 PromptContext를 구성하고 PromptBuilder로 프롬프트를 생성하는 간단한 예입니다:

PromptContext ctx = PromptContext.builder()
    .userQuery("무엇이 빅뱅이론을 지지하나요?")
    .web(List.of(webContent1, webContent2))
    .rag(List.of(vectorSegment1, vectorSegment2))
    .memory("과거 대화 요약...")
    .history("전 세션에서의 질문과 답변...")
    .location(new Location(lat, lng, accuracy, capturedAt))
    .locationAddress("대전광역시 서구")
    .cognitiveState(new CognitiveState("tutor"))
    .build();

String promptBody = promptBuilder.build(ctx);
String instructions = promptBuilder.buildInstructions(ctx);
String finalPrompt = instructions + "
" + promptBody;


프롬프트 빌더는 모든 섹션을 자동으로 정렬하며, 최대 4개의 중요한 키워드를 ### MUST_INCLUDE 섹션에 추출하여 모델에게 반드시 포함하도록 지시합니다. 위치 컨텍스트가 포함되면 lat, lng, accuracy(m), capturedAt, address 필드를 표시합니다.

프롬프트 정책 테스트

프롬프트 정책 준수 여부는 테스트 클래스로 검증됩니다. 예를 들어 PromptBuilderNoConcatTest는 ChatService에 문자열 직접 연결이 없는지 확인합니다. EvidencePromptBuilderTest와 PromptGuardTest는 증거 섹션과 가드 정책이 프롬프트에 제대로 반영되는지 검증합니다.

Retrieval, Fusion & Re-ranking
SelfAsk, Analyze, Web, Vector 단계

Retrieval 과정은 여러 하위 단계로 구성됩니다. SelfAskHandler는 질문이 두루뭉술할 때 명확화 질문을 생성합니다. 예를 들어, 사용자가 ‘그는 언제 태어났나요?’라고 묻는다면 SelfAsk는 그가 누구인지 알아내기 위한 추가 질문을 던질 수 있습니다.
AnalyzeHandler는 질의의 품질을 평가하고 오타나 비상식적 표현을 수정합니다. QueryComplexityClassifier와 QueryComplexityGate를 통해 질의의 복잡도를 분류하며, 필요 시 SelfAsk 단계를 우선 활성화합니다.
WebSearchHandler는 SerpAPI, Bing API, Google CSE 등 여러 프로바이더를 통해 웹 문서를 수집합니다. 검색 결과는 신뢰도 점수와 함께 WebDocument로 래핑됩니다.
VectorDbHandler는 Pinecone와 같은 백엔드에서 임베딩 세그먼트를 검색합니다. rag.search.top-k 속성에 설정된 개수만큼의 벡터 세그먼트를 반환하며, 언어 전처리를 위해 open-korean-text 토크나이저를 사용할 수 있습니다.
MemoryHandler는 장기 기억에서 이전 대화나 세션 정보를 검색합니다. memory.read.max-turns와 memory.evidence.max-turns 속성을 통해 검색 범위를 조절할 수 있습니다.
LocationAwareHandler와 LocationAnswerHandler는 질문이 위치와 관련된지 판별하고, 해당하는 경우 웹/벡터 검색을 건너뛰고 위치 서비스로 종료합니다.

Fusion – Reciprocal Rank Fusion(RRF)

웹, 벡터, 메모리 결과가 수집된 후 ReciprocalRankFuser가 RRF 알고리즘을 적용합니다. 융합 파라미터는 ranking.rrf.k로 조정할 수 있습니다. 여러 소스에서 등장하는 동일한 문서는 높은 점수를 얻습니다.

Cross-Encoder Re-ranking

RRF 이후에는 크로스 인코더 재랭킹이 선택적으로 수행됩니다. abandonware.reranker.backend는 다음 세 가지 값 중 하나를 취할 수 있습니다:

onnx-runtime: 로컬 ONNX 런타임을 사용하여 BERT 기반 크로스 인코더로 정확한 재랭킹을 수행합니다. abandonware.reranker.onnx.model-path 및 execution-provider 속성을 사용하여 모델 위치와 CPU/GPU 실행을 지정합니다.

embedding-model: 기존 임베딩 기반 간단한 재랭커를 사용합니다. 정확도는 낮지만 속도가 빠릅니다.

noop: 재랭킹을 생략하고 RRF 결과 그대로 사용합니다.
재랭킹 후 상위 ranking.rerank.ce.topK 개 문서만 남겨 프롬프트에 전달합니다.

Fusion 모드와 온도

HybridRetriever는 벡터 및 웹 결과를 결합하는 방식을 retrieval.fusion.mode 속성을 통해 제어합니다. weightedSoftmax 모드는 RRF 점수를 소프트맥스 온도로 변환하여 가중 평균을 계산합니다. retrieval.fusion.softmax.temperature는 이때 사용되는 온도로, 값이 낮을수록 상위 결과에 더 많은 가중치가 부여됩니다.

Location Features
위치 의도 감지 및 흐름

사용자의 질문에 위치 의도가 포함되어 있으면, 검색 대신 LocationService가 질의에 응답합니다. 예를 들어 ‘나 지금 어디야?’, ‘주변 약국 알려줘’, ‘대전 시청까지 걸리는 시간’ 등의 질문은 위치 기능으로 분기됩니다.
LocationAnswerHandler는 위치 의도를 감지하여 검색 체인을 중단하고 위치 서비스로 전달합니다. 이때 최근 위치 이벤트(LocationEvent)가 존재해야 정확한 주소를 반환할 수 있습니다.

주요 클래스

LocationController: REST API 엔드포인트를 정의합니다. /api/location/consent/on으로 위치 수집 동의를 설정하고, /api/location/events로 사용자의 위치 이벤트를 수신합니다.

LocationService: 위치 데이터 저장 및 검색, 위치 의도 감지 로직을 구현합니다.

ReverseGeocodingClient: 위도/경도를 주소로 변환하는 인터페이스입니다. 기본 구현은 KakaoReverseGeocodingClient로, Kakao REST 키가 필요합니다.

KakaoPlacesClient: 주변 장소(약국, 병원, 카페 등)를 찾기 위해 Kakao Places API를 호출합니다. kakao.rest-key 환경 변수가 필요합니다.

TmapDirectionsClient: 출발지에서 목적지까지의 최단 경로와 예상 시간을 조회합니다. tmap.app-key가 필요합니다.

API 엔드포인트 및 예제

위치 기능을 테스트하려면 다음과 같은 cURL 명령을 사용할 수 있습니다:

# 동의 켜기
curl -X POST http://localhost:8080/api/location/consent/on -H "X-User-Id: alice"
# 좌표 적재
curl -X POST http://localhost:8080/api/location/events -H "Content-Type: application/json" -H "X-User-Id: alice" -d '{"lat":36.35,"lng":127.33,"ts":"2025-08-21T09:00:00Z"}'
# 질의 예: 나 지금 어디야?


위 API를 통해 사용자의 현재 위치를 설정하고, 이후 Chat API에 ‘나 지금 어디야?’를 보내면 LocationService가 최근 좌표를 역지오코딩하여 주소를 응답합니다. 주변 장소나 이동 시간에 대한 질문도 비슷한 흐름으로 처리됩니다.

환경 변수와 설정

위치 기능을 활성화하려면 다음 환경 변수가 설정되어 있어야 합니다:

KAKAO_REST_KEY: Kakao Reverse Geocoding 및 Places API 키

GOOGLE_API_KEY: Google Maps/Geocoding 키 (선택적으로 Tmap 대체 시 사용)

TMAP_APP_KEY: Tmap Directions API 키
또한 location.enabled=true 속성이 켜져 있어야 하며, 기본적으로 application.yml에서 활성화되어 있습니다.

Configuration
주요 설정 요약

프로젝트의 동작은 application.yml과 application.properties에 정의된 수많은 설정 값에 의해 제어됩니다. 여기서는 운영에 필수적인 주요 키를 요약합니다. 값은 기본값이며 환경에 맞게 조정할 수 있습니다:

키	기본값	설명
rag.search.top-k	10	벡터 및 웹 검색 시 가져올 문서 수
ranking.rrf.k	50	RRF 융합에서 고려할 순위 k
ranking.rerank.ce.topK	12	크로스 엔코더 재랭킹 후 유지할 문서 수
router.moe.tokens-threshold	1200	MOE 승격을 트리거하는 토큰 수
router.moe.complexity-threshold	0.55	복잡도 승격 임계값 (0~1)
router.moe.uncertainty-threshold	0.40	불확실성 승격 임계값
router.moe.web-evidence-threshold	0.60	웹 증거 승격 임계값
router.moe.escalate-on-rigid-temp	true	비정상 온도 사용 시 승격 여부
router.moe.learning-intents	CURATION,RULE_INDUCTION,KB_UPDATE,ANALYSIS	MOE 적용 의도
abandonware.reranker.backend	onnx-runtime	재랭킹 백엔드 선택
selfask.timeout-seconds	10	SelfAsk 단계 전체 타임아웃(초)
selfask.per-request-timeout-ms	5000	SelfAsk API 호출 타임아웃(ms)
tavily.enabled	true	Tavily 웹 검색 사용 여부
tavily.api.url	https://api.tavily.com/search
	Tavily API URL
tavily.max-results	5	Tavily 검색 결과 수
tavily.timeout-ms	3000	Tavily 호출 타임아웃(ms)
authority.tier-weights.official	1.00	공식 출처 가중치
authority.tier-weights.guide	0.85	가이드 출처 가중치
authority.tier-weights.wiki	0.80	위키 출처 가중치
authority.tier-weights.community	0.65	커뮤니티 출처 가중치
memory.read.max-turns	8	메모리에서 읽어올 과거 턴 수
memory.evidence.max-turns	6	메모리 증거 최대 턴 수
guard.evidence_regen.enabled	true	증거 부족 시 재생성/승격 기능
guard.evidence_regen.min_web_docs	1	재생성 트리거 웹 문서 최소 수
guard.evidence_regen.min_total_docs	2	재생성 트리거 전체 문서 최소 수

이 외에도 매우 많은 세부 설정이 존재합니다. 테스트 환경에서는 기본값을 유지하되, 운영 환경에서는 API 키, 모델 이름, 온도, 토큰 제한, 시간 제한 등을 반드시 환경 변수나 application-*.yml 파일로 재정의하십시오.

외부 API 키 설정

외부 서비스와 통신하기 위해 다음 환경 변수를 설정해야 합니다:

KAKAO_REST_KEY – Kakao Reverse Geocoding 및 Places API 키

GOOGLE_API_KEY – Google Maps 및 Geocoding API 키 (선택 사항)

TMAP_APP_KEY – Tmap Directions API 키

OPENAI_API_KEY – OpenAI Chat API 키 (LangChain4j OpenAI 클라이언트 사용 시 필요)
API 키는 소스 코드에 하드코딩하지 않고 반드시 환경 변수나 외부 비밀 저장소를 통해 주입해야 합니다.

Run & Build
로컬 실행

프로젝트를 로컬에서 실행하려면 다음 명령을 사용하십시오. Java 17이 설치되어 있어야 하며, Gradle Wrapper를 통해 필요한 종속성이 자동으로 다운로드됩니다:

cd src
./gradlew clean bootRun


bootRun 태스크는 내장 Tomcat 서버에서 애플리케이션을 실행합니다. 기본 포트는 8080이며 server.port 속성으로 변경할 수 있습니다.

패키징 및 배포

배포용 JAR 파일을 빌드하려면 다음 명령을 실행하십시오:

cd src
./gradlew build
java -jar build/libs/*.jar


bootJar 태스크가 비활성화되어 있으므로, 실행 가능한 JAR는 Spring Boot의 레이어드 아카이브로 생성됩니다. 운영 환경에서 java -jar로 실행하거나 Docker 이미지로 패키징할 수 있습니다.

Java 및 Gradle 버전

Java 17 이상이 요구됩니다. 다른 버전에서는 레코드(record) 기능 등 최신 문법을 사용할 수 없습니다.

Gradle Wrapper(./gradlew)는 프로젝트 내에서 관리되며 버전 8.x가 권장됩니다. 수동으로 설치된 Gradle을 사용할 필요가 없습니다.

Quality Gates (Static Rules)

코드 품질을 유지하기 위해 여러 정적 규칙이 설정되어 있습니다. 개발 중 아래와 같은 패턴을 발견하면 수정해야 합니다. 규칙 위반은 CI 단계에서 실패 원인이 됩니다.

조기 컷 방지

핸들러에서 accumulator.size() >= topK와 같이 결과 수가 일정 이상이면 false를 반환하여 체인을 종료하는 코드가 발견되면 안 됩니다. fail-soft 정책에 따라 마지막까지 결과를 누적해야 합니다.
정규식으로 검출되는 패턴:

accumulator\.size\(\)\s*>?=\s*topK
return\s+false;

임베딩 배치 누락

벡터 생성 시 embedAll()을 사용하여 배치 임베딩을 수행해야 합니다. 단일 문서에 embed()를 반복 호출하는 패턴은 성능 저하를 유발합니다.
검출 패턴:

\bembed\(
\bembedAll\(


embedAll() 사용이 없는 경우 성능 문제를 야기할 수 있으므로 점검이 필요합니다.

프롬프트 직접 연결 금지

프롬프트를 문자열로 직접 이어붙이는 코드는 금지되어 있습니다. ChatService 등에서 "\n" 또는 "+" 연산으로 프롬프트를 만들면 정책 위반입니다. 항상 PromptBuilder를 사용하십시오.
정규식 예시:

"\n"\s*\+\s*
\+\s*"\n"

혼합 LangChain4j 버전 탐지

모든 의존성은 LangChain4j 1.0.1을 사용해야 합니다. dev.langchain4j:.*:0.2. 버전이 발견되면 빌드 실패입니다.
검색 패턴:

dev\.langchain4j:.*:0\.2\.


정적 도구(rg -n, grep -RInE)를 사용하여 코드베이스 전반에서 이러한 패턴을 검색하고 조기에 수정하십시오.

Testing
자동화된 테스트 클래스

PipelineIntegrationTest

PromptGuardTest

EvidencePromptBuilderTest

PromptBuilderNoConcatTest

ContextualScorerTest

SuperBuilderWiringTest

ModelRouterCoreTest

ModelRouterBeanTest

RouteSignalVisibilityTest

ModelRouterRoutingTest

ModelEscalationTest

EvidenceAwareGuardTest

EvidenceAwareGuardRegenerateTest

DynamicRetrievalHandlerChainOrderTest

MatrixTransformerTest

ChatApiControllerLocationTest

ChatApiControllerNearbyTest

ChatApiControllerTravelTimeTest

LlmCallBudgetTest

NeuralPathFormationServiceTest

LangchainVersionTest

NearbyPharmaciesTest

FormattersEtaTest

LocationServicePersonalisationTest

TravelTimeToOfficeTest

ConfigKeysRetentionTest
이러한 테스트는 라우팅, 프롬프트 구성, 위치 기능, 체인 순서, 모형 승격, 증거 가드 등을 자동으로 검증합니다. 새 기능을 추가할 때는 반드시 관련 테스트를 작성하여 품질 게이트를 통과해야 합니다.

수동 시나리오

아래는 수동으로 시스템을 검증할 수 있는 시나리오입니다. 실제 서버를 실행한 후 Chat API 또는 위치 API를 호출하여 예상대로 동작하는지 확인하십시오:

현재 위치 질의 – /api/location/consent/on과 /api/location/events로 좌표를 전송한 뒤, Chat API에 ‘나 지금 어디야?’를 보내면 최근 좌표가 역지오코딩되어 주소가 반환되어야 합니다.

주변 약국 검색 – 위치 이벤트 후 Chat API에 ‘근처 약국 알려줘’ 를 보내면 KakaoPlacesClient가 호출되어 약국 리스트와 거리 정보가 제공됩니다.

이동 시간 요청 – /api/location/events로 현재 좌표를 제공한 뒤 ‘시청까지 소요시간’을 요청하면 TmapDirectionsClient를 통해 예상 시간과 경로가 반환되어야 합니다.

복잡한 질문으로 MOE 승격 – 긴 질문(예: ‘빅뱅 이론과 양자 중력의 차이점을 비교하고 역사적 맥락을 설명해줘’)을 보내면 토큰 수와 복잡도 임계값을 넘어서므로 모델이 자동으로 MOE로 승격되는지 확인합니다.

핸들러 부분 실패 유지 – 인터넷 연결을 끊어 웹 검색이 실패하도록 한 후 간단한 질문을 보내면 벡터 검색과 메모리 검색만으로 답변을 생성해야 합니다. fail-soft 정책이 동작하는지 확인하십시오.

Troubleshooting

시스템을 운영하면서 발생할 수 있는 일반적인 문제와 해결 방법을 정리합니다:

PDF 파일 읽기 실패 – 로그에 PDFBox PDDocument.load(InputStream) 관련 서명 오류가 발생하면 PDF의 디지털 서명을 해제하거나 최신 pdfbox 버전과 호환되는 문서인지 확인하십시오.

DirectionsClient 심볼 누락 – NoSuchMethodError: DirectionsClient와 같은 오류가 발생하면 Tmap/Google API 클라이언트 라이브러리 버전 불일치일 수 있습니다. build.gradle의 BOM을 확인하여 버전을 일치시킵니다.

웹 검색만 호출되는 현상 – 벡터 검색이 실행되지 않고 웹 검색만 동작한다면 질의가 위치 의도로 분류되었거나 RetrievalOrderService의 동적 판별 로직이 우선 순위를 조정했을 가능성이 있습니다. 로그의 RetrievalOrderService 메시지를 확인하십시오.

Rigid-temp 온도 오류 – 강제 온도를 사용한 프롬프트에서 모델이 비정상적인 답변을 내놓는다면 router.moe.escalate-on-rigid-temp를 활성화하여 MOE로 승격하거나 기본 temperature를 사용하십시오.

API 키 미설정 – Kakao/Google/Tmap 키가 설정되지 않은 경우 위치 기능이 제한됩니다. 환경 변수를 설정한 후 애플리케이션을 재시작하십시오.

메모리 누수 – 메모리 핸들러가 너무 많은 과거 턴을 로딩하면 Out-Of-Memory 문제가 발생할 수 있습니다. memory.read.max-turns와 memory.evidence.max-turns 값을 줄이고, 메모리 저장소의 만료 정책을 점검하십시오.

Contributing & Coding Standards

본 프로젝트는 팀 내외의 기여를 환영합니다. 하지만 일관된 품질을 유지하기 위해 다음 표준을 준수해야 합니다:

핸들러 추가 가이드 – 새 데이터 소스나 기능을 추가하려면 com.example.lms.service.rag.handler 패키지에 새로운 핸들러를 구현하고, DefaultRetrievalHandlerChain 또는 DynamicRetrievalHandlerChain에 등록하십시오. 핸들러는 상태를 가지지 않아야 하며, 실패하더라도 체인을 중단하지 않고 빈 결과를 반환해야 합니다.

설정 우선 원칙 – 하드코딩된 값 대신 application.yml/application.properties 또는 환경 변수로 모든 매직 넘버를 노출해야 합니다. 예: 토큰 임계값, 검색 결과 수, 온도, API 키 등.

세션 격리 – ChatSessionScope와 관련된 객체는 세션 간 공유되지 않아야 합니다. 싱글톤 빈에 상태를 저장하지 말고, 필요한 경우 @Scope(value = WebApplicationContext.SCOPE_SESSION)를 사용합니다.

테스트 우선 – 새 기능을 구현할 때는 반드시 단위/통합 테스트를 작성하십시오. 기존 테스트 스위트에 통합되도록 src/test/java 경로에 배치합니다.

코드 스타일 – Java 코드에서 @Override를 명시적으로 선언하고, 메서드 매개변수와 지역 변수는 명확한 이름을 사용합니다. Lombok 애너테이션(@Getter, @Setter, @Builder)을 적절히 활용하되, 코드 가독성을 해치지 않도록 주석과 Javadoc을 추가합니다.

문서화 – 새 핸들러나 서비스, 설정 키를 추가할 때는 반드시 README와 RUNBOOK.md에 해당 내용을 반영합니다. 운영팀이 변경점을 쉽게 파악할 수 있도록 변경 사항을 명시하십시오.

License (선택)

본 리포지토리는 내부용으로 제공되며, 오픈소스 라이선스가 명시되지 않았습니다. 외부 공개 시에는 적절한 라이선스를 추가해야 합니다.
