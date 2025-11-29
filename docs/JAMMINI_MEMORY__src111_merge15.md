# Jammini Memory — src111_merge15 (추가기능 전용)
(이 파일은 운영 메모용이며, 빌드에는 포함되지 않습니다)

* Soak 테스트 API — 쿼리셋 자동 샘플링·지표 집계
  - GET /internal/soak/run?k=10&topic=all|genshin|default
  - 구현: service/soak/SoakTestService.java, api/internal/SoakApiController.java, service/soak/SoakQueryProvider.java

* Search Probe(디버그) API — 하이브리드 체인 리플리카 실행/추적
  - POST /api/probe/search (useWeb, useRag, officialSourcesOnly, webTopK, intent 등)
  - 구현: probe/SearchProbeController.java, probe/SearchProbeService.java, probe/dto/*
  - 토글/보안: probe.search.enabled, probe.admin-token

* 페더레이티드 벡터 스토어 — Pinecone+Upstash 다중 스토어 통합
  - 구현: vector/FederatedEmbeddingStore.java, config/FederatedVectorStoreConfig.java
  - 라우팅: vector/TopicRoutingSettings.java (vector.routing.*)
  - 토글: retrieval.vector.enabled=true

* Upstash 백드 웹 캐시 + 레이트리미터 — 2계층 캐시(로컬 Caffeine→Upstash)
  - 구현: infra/upstash/UpstashBackedWebCache.java, infra/upstash/UpstashRateLimiter.java, config/WebInfraConfig.java
  - 설정: upstash.cache.ttl-seconds, Upstash URL/API-KEY, naver.search.cache.*

* 동적 리트리벌 체인(오더링 서비스)
  - 구현: service/rag/handler/DynamicRetrievalHandlerChain.java, strategy/RetrievalOrderService.java
  - 특징: Self-Ask/Analyze 이후 KG 핸들러 포함

* 지식그래프 핸들러(옵션)
  - 구현: service/rag/handler/KnowledgeGraphHandler.java

* ONNX 크로스-인코더 추론 로컬화
  - 구현: service/onnx/OnnxRuntimeService.java, service/onnx/OnnxCrossEncoderReranker.java
  - 토글: onnx.enabled

* 바이-인코더 예비 재랭커(속도형)
  - 구현: service/rag/BiEncoderReranker.java, config/RerankerConfig.java

* Weighted-RRF 퓨저(멀티소스 융합)
  - 구현: service/rag/fusion/RrfFusion.java, rag/fusion/WeightedRRF.java

* 도메인 프로파일 로더 + 화이트리스트
  - 구현: service/rag/auth/DomainProfileLoader.java, service/rag/auth/DomainWhitelist.java
  - 설정: naver.filters.domain-allowlist.*

* 요청 상관관계 트레이싱
  - 구현: trace/RequestIdHeaderFilter.java, web/TraceFilter.java, trace/TraceContext.java
  - 헤더: X-Request-Id, X-Session-Id

* SSE 텔레메트리 퍼블리셔(스트리밍 로깅)
  - 구현: telemetry/SseEventPublisher.java, telemetry/LoggingSseEventPublisher.java, config/TelemetryConfig.java

* 네이버 웹검색 헤징/타임아웃/캐시
  - 구현: service/NaverSearchService.java, service/rag/AnalyzeWebSearchRetriever.java
  - 설정: naver.hedge.enabled, naver.hedge.delay-ms, naver.search.timeout-ms, naver.search.web-top-k

* 적응형 번역(리액티브) API
  - POST /api/adaptive-translate
  - 구현: service/AdaptiveTranslationService.java, controller/AdaptiveTranslateController.java
  - 특징: 내부 TM/성공률 기반 의사결정

* 번역 규칙 학습/관리 API 세트
  - /api/translate/train, /api/translate/rules
  - 구현: api/TranslateController.java, service/TranslationTrainingService.java, domain/TranslationRule.java

* Gemini 지식 큐레이션 파이프라인
  - 구현: learning/gemini/GeminiCurationService.java, LearningWriteInterceptor.java, GeminiCurationPromptBuilder.java

* 세션 부트스트랩 진단 엔드포인트
  - GET /bootstrap
  - 구현: api/SessionBootstrapController.java

* n8n Webhook 연동(HMAC 검증+비동기 잡 큐)
  - POST /hooks/n8n
  - 구현: api/N8nWebhookController.java, integrations/n8n/SignatureVerifier.java, jobs/JobService.java

* 이미지 생성 플러그인(격리 네임스페이스)
  - POST /api/image-plugin
  - 구현: plugin/image/ImageGenerationPluginController.java, plugin/image/OpenAiImageService.java

* OCR 통합(테서랙트 반사 로딩 + 스팬/청크 모델)
  - 구현: service/ocr/OcrService.java, service/ocr/BasicTesseractOcrService.java, OcrSpan.java, OcrChunk.java, app/.../OcrTesseractReflect.java

* 지식 큐레이션 스케줄러/배치
  - 구현: agent/KnowledgeCurationScheduler.java, learning/gemini/GeminiBatchService.java

* 인덱싱 스케줄러(증분 임베딩 파이프라인)
  - 구현: scheduler/IndexingScheduler.java, service/EmbeddingStoreManager.java

* 권장 답변 안전장치 — 응답 후처리/정책
  - 구현: guard/AnswerSanitizer.java, guard/GenshinRecommendationSanitizer.java

* 툴/플러그인 매니페스트(웹 검색 도구 포함)
  - 예시: app/resources/docs/tool_manifest__kchat_gpt_pro.json, integrations/TavilyWebSearchRetriever.java

* 표준 컨텍스트 포맷 계약
  - 형식: {id, title, snippet, source, score, rank}
  - 사용처: service/rag/* 전반
