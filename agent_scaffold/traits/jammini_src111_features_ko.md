# Jammini Memory — src111_merge15 (추가기능 전용)

* **Soak 테스트 API** — 쿼리셋 자동 샘플링·지표 집계
  * 엔드포인트: `GET /internal/soak/run?k=10&topic=all|genshin|default`
  * 구현: `service/soak/SoakTestService.java`, `api/internal/SoakApiController.java`, `service/soak/SoakQueryProvider.java`
  * 효과: 적중/근거율/레이턴시 정리 JSON 반환으로 회귀·튜닝에 활용

* **Search Probe(디버그) API** — 하이브리드 체인 리플리카 실행/추적
  * 엔드포인트: `POST /api/probe/search` (파라미터: `useWeb`, `useRag`, `officialSourcesOnly`, `webTopK`, `intent` 등)
  * 구현: `probe/SearchProbeController.java`, `probe/SearchProbeService.java`, `probe/dto/*`
  * 토글/보안: `probe.search.enabled`, `probe.admin-token`

* **페더레이티드 벡터 스토어** — Pinecone+Upstash 다중 스토어 통합
  * 구현: `vector/FederatedEmbeddingStore.java`, `config/FederatedVectorStoreConfig.java`
  * 라우팅: `vector/TopicRoutingSettings.java` (`vector.routing.*`로 토픽별 가중 분배)
  * 토글: `retrieval.vector.enabled=true`

* **Upstash 백드 웹 캐시 + 레이트리미터** — 2계층 캐시(로컬 Caffeine→Upstash)
  * 구현: `infra/upstash/UpstashBackedWebCache.java`, `infra/upstash/UpstashRateLimiter.java`, `config/WebInfraConfig.java`
  * 토글/설정: `upstash.cache.ttl-seconds`, Upstash URL/API-KEY, `naver.search.cache.*`

* **동적 리트리벌 체인(오더링 서비스)** — Web/Vector/KG 순서 자동 결정
  * 구현: `service/rag/handler/DynamicRetrievalHandlerChain.java`, `strategy/RetrievalOrderService.java`
  * 특징: Self-Ask/Analyze 이후 **KG(지식그래프) 핸들러**까지 포함한 동적 오더링

* **지식그래프 핸들러(옵션)** — KG 기반 보강 검색 단계
  * 구현: `service/rag/handler/KnowledgeGraphHandler.java`
  * 사용: 동적 체인에서 질의 특성에 따라 우선 검색

* **ONNX 크로스-인코더 추론 로컬화** — 서버 내 ONNX 런타임
  * 구현: `service/onnx/OnnxRuntimeService.java`, `service/onnx/OnnxCrossEncoderReranker.java`
  * 토글: `onnx.enabled`(유형별 모델 경로 포함)

* **바이-인코더 예비 재랭커(속도형)**
  * 구현: `service/rag/BiEncoderReranker.java`, `config/RerankerConfig.java`
  * 용도: 빠른 1-패스 필터 뒤 정밀 2-패스(크로스-인코더) 결합

* **Weighted-RRF 퓨저(멀티소스 융합)**
  * 구현: `service/rag/fusion/RrfFusion.java`, `rag/fusion/WeightedRRF.java`
  * 효과: 로컬·웹·KG 결과의 보수적 결합 안정성

* **도메인 프로파일 로더 + 화이트리스트** — 분야별 공식 소스 제한
  * 구현: `service/rag/auth/DomainProfileLoader.java`, `service/rag/auth/DomainWhitelist.java`
  * 설정: `naver.filters.domain-allowlist.*` 프로파일(정부/학술/뉴스/게임 등 티어)

* **요청 상관관계 트레이싱** — 헤더 기반 상관키 주입
  * 구현: `trace/RequestIdHeaderFilter.java`, `web/TraceFilter.java`, `trace/TraceContext.java`
  * 헤더: `X-Request-Id`, `X-Session-Id` 전 구간 전달

* **SSE 텔레메트리 퍼블리셔(스트리밍 로깅)**
  * 구현: `telemetry/SseEventPublisher.java`, `telemetry/LoggingSseEventPublisher.java`, `config/TelemetryConfig.java`
  * 용도: 체인 단계 이벤트 스트림으로 실시간 관찰

* **네이버 웹검색 헤징/타임아웃/캐시** — 품질·지연 관리
  * 구현: `service/NaverSearchService.java`, `service/rag/AnalyzeWebSearchRetriever.java`
  * 설정: `naver.hedge.enabled`, `naver.hedge.delay-ms`, `naver.search.timeout-ms`, `naver.search.web-top-k`

* **적응형 번역(리액티브) API** — TranslationMemory+정책 기반
  * 엔드포인트: `POST /api/adaptive-translate`
  * 구현: `service/AdaptiveTranslationService.java`, `controller/AdaptiveTranslateController.java`
  * 특징: Mono 체인 유지(논블로킹), 내부 TM/성공률 기반 의사결정

* **번역 규칙 학습/관리 API 세트**
  * 엔드포인트(예): `/api/translate/train`, `/api/translate/rules`
  * 구현: `api/TranslateController.java`, `service/TranslationTrainingService.java`, `domain/TranslationRule.java`

* **Gemini 지식 큐레이션 파이프라인** — 생성 결과를 학습 이벤트로 강화
  * 구현: `learning/gemini/GeminiCurationService.java`, `LearningWriteInterceptor.java`, `GeminiCurationPromptBuilder.java`
  * 흐름: Curation→Delta 없으면 `MemoryReinforcementService`로 폴백

* **세션 부트스트랩 진단 엔드포인트**
  * 엔드포인트: `GET /bootstrap`
  * 구현: `api/SessionBootstrapController.java`
  * 반환: 상태/원격 IP/쿠키 존재 플래그

* **n8n Webhook 연동(HMAC 검증+비동기 잡 큐)**
  * 엔드포인트: `POST /hooks/n8n`
  * 구현: `api/N8nWebhookController.java`, `integrations/n8n/SignatureVerifier.java`, `jobs/JobService.java`
  * 결과: 서명 실패 401, 수락 시 202+jobId

* **이미지 생성 플러그인(격리 네임스페이스)**
  * 엔드포인트: `POST /api/image-plugin`
  * 구현: `plugin/image/ImageGenerationPluginController.java`, `plugin/image/OpenAiImageService.java`
  * 비고: 기존 gptapi 경로와 충돌 방지

* **OCR 통합(테서랙트 반사 로딩 + 스팬/청크 모델)**
  * 구현: `service/ocr/OcrService.java`, `service/ocr/BasicTesseractOcrService.java`, `OcrSpan.java`, `OcrChunk.java`, `app/.../OcrTesseractReflect.java`
  * 비고: Tess4J 미존재 시 placeholder 작동(중단 없이 통과)

* **지식 큐레이션 스케줄러/배치**
  * 구현: `agent/KnowledgeCurationScheduler.java`, `learning/gemini/GeminiBatchService.java`
  * 용도: 오프라인 큐레이션/학습 데이터 축적

* **인덱싱 스케줄러(증분 임베딩 파이프라인)**
  * 구현: `scheduler/IndexingScheduler.java`, `service/EmbeddingStoreManager.java`
  * 비고: 신규 문서 자동 임베딩→벡터 스토어 반영

* **권장 답변 안전장치** — 응답 후처리/정책
  * 구현: `guard/AnswerSanitizer.java`, `guard/GenshinRecommendationSanitizer.java`
  * 용도: 게임/추천 영역 오판 완화 및 안전 출력

* **툴/플러그인 매니페스트(웹 검색 도구 포함)**
  * 구현/예시: `app/resources/docs/tool_manifest__kchat_gpt_pro.json`, `integrations/TavilyWebSearchRetriever.java`
  * 용도: 에이전트 도구 선언과 스키마 정리

* **표준 컨텍스트 포맷 계약**
  * 형식: `{id, title, snippet, source, score, rank}`
  * 사용처: `service/rag/*` 전반(웹/벡터/KG 결과를 통일 포맷으로 상류 전달)
