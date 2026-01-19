# GPT‑PRO 에이전트 통합 가이드 (자동 생성)

본 문서는 `src111_mergawe15.zip` 코드베이스를 **GPT‑PRO 에이전트**가 읽고 조작하기 쉽게 요약·인덱싱한 자료입니다.
주요 클래스에는 `[GPT‑PRO‑AGENT]` Javadoc 헤더가 삽입되었습니다(동작 무변).

## 1) 구조 요약 & 연결 포인트
- Java 파일 수: 2326
- 헤더 주석이 새로 추가된 파일: 2231
- 주요 모듈 태그(경로 감지 기반):
  - Search Probe, Dynamic Retrieval Chain, Federated Vector Store, Upstash Cache, ONNX/BI Reranker,
    Weighted‑RRF Fusion, Domain Whitelist, Telemetry/Tracing, Translation, Gemini Curation, n8n Webhook,
    OCR, Schedulers, Answer Guard, Web Search Tool

## 2) 공개 REST 엔드포인트(부분 스캔)

- **app/src/main/java/com/abandonware/ai/agent/FlowController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/nn/GradientDebugController.java**
  - ? "/diag/nn/gradients"
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/web/AdminController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/web/AgentTraceController.java**
  - ? (path in annotation)
  - ? "/events"

- **app/src/main/java/com/abandonware/ai/agent/web/IdentityController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/web/MessageController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/web/RoomController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/web/SessionController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/agent/web/SigmoidController.java**
  - ? (path in annotation)

- **app/src/main/java/com/abandonware/ai/api/LLMController.java**
  - ? (path in annotation)

- **app/src/main/java/com/example/lms/api/internal/SoakApiController.java**
  - ? "/internal/soak"
  - ? "/run"

- **app/src/main/java/com/example/lms/api/internal/SoakReportController.java**
  - ? "/internal/soak"
  - ? "/report"

- **app/src/main/java/com/example/lms/mcp/McpSessionRouter.java**
  - ? (path in annotation)
  - ? "/hello"

- **app/src/main/java/com/example/lms/probe/SearchProbeController.java**
  - ? "/api/probe"
  - ? "/search"

- **app/src/main/java/com/example/telemetry/TelemetryController.java**
  - ? (path in annotation)
  - ? "/stream"

- **cfvm-raw/src/main/java/com/example/lms/cfvm/CfvmAdminController.java**
  - ? (path in annotation)
  - ? "/buffer"

- **extras/gap15-stubs_v1/src/main/java/com/abandonwareai/resilience/cfvm/CfvmController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/FlowController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/web/AdminController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/web/AgentTraceController.java**
  - ? (path in annotation)
  - ? "/events"

- **lms-core/src/main/java/com/abandonware/ai/agent/web/IdentityController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/web/MessageController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/web/RoomController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/web/SessionController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/agent/web/SigmoidController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/api/N8nWebhookController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/api/SessionBootstrapController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/api/TranslateController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/api/internal/OcrHealthController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/api/internal/SoakApiController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/controller/AdaptiveTranslateController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/example/lms/cfvm/CfvmAdminController.java**
  - ? (path in annotation)
  - ? "/buffer"

- **lms-core/src/main/java/com/abandonware/ai/plugin/image/ImageGenerationPluginController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/probe/SearchProbeController.java**
  - ? (path in annotation)

- **lms-core/src/main/java/com/abandonware/ai/telemetry/OpsSseController.java**
  - ? (path in annotation)
  - ? "/ops"

- **src/main/java/com/abandonwareai/resilience/cfvm/CfvmController.java**
  - ? (path in annotation)

- **src/main/java/com/abandonwareai/zerobreak/controller/ZeroBreakAdminController.java**
  - ? (path in annotation)
  - ? "/plan"
  - GET "/plan/{id}"
  - ? "/dry-run"

- **src/main/java/com/example/lms/api/AdminController.java**
  - ? (path in annotation)
  - GET (path in annotation)

- **src/main/java/com/example/lms/api/AttachmentController.java**
  - ? (path in annotation)
  - ? "/upload"

- **src/main/java/com/example/lms/api/ChatApiController.java**
  - ? (path in annotation)
  - ? "/stream"

- **src/main/java/com/example/lms/api/ChatApiControllerExtra.java**
  - ? (path in annotation)
  - GET (path in annotation)

- **src/main/java/com/example/lms/api/DomainProfileController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/FeedbackController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/FileUploadController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/IntegrationController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/KakaoAdminController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/KakaoOAuthController.java**
  - ? (path in annotation)
  - GET (path in annotation)

- **src/main/java/com/example/lms/api/KakaoTriggerController.java**
  - ? (path in annotation)
  - GET "/trigger"
  - ? "/trigger"

- **src/main/java/com/example/lms/api/KakaoUuidController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/KakaoWebhookController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/ModelSettingsController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/N8nWebhookController.java**
  - ? "/hooks/n8n"
  - ? "/hooks/n8n/{jobId}"

- **src/main/java/com/example/lms/api/PkiUploadPageController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/PkiValidationController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/SearchFeedbackController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/SessionBootstrapController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/SettingsController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/TasksApiController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/TranslateController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/api/internal/SoakApiController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/audio/AudioController.java**
  - ? (path in annotation)
  - ? "/tts"
  - ? "/stt"

- **src/main/java/com/example/lms/controller/AdaptiveTranslateController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/controller/RentalController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/controller/TrainingController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/controller/TranslationController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/dto/help/ContextHelpController.java**
  - ? "/api/v1/help"
  - ? "/context"

- **src/main/java/com/example/lms/learning/gemini/LearningController.java**
  - ? (path in annotation)

- **src/main/java/com/example/lms/location/api/LocationController.java**
  - ? (path in annotation)


## 3) 구성 키(부분 스캔)
- `activation.accepted_policies.0`
- `activation.accepted_policies.1`
- `activation.accepted_policies.2`
- `activation.accepted_policies.3`
- `activation.headers.flag`
- `activation.headers.plan`
- `activation.headers.policy`
- `activation.headers.token`
- `activation.require_signed_token`
- `adapter.rag-light.enabled`
- `addons.budget.default-ms`
- `addons.complexity.enabled`
- `addons.ocr.enabled`
- `addons.ocr.top-k`
- `addons.onnx.max-concurrent`
- `addons.onnx.queue-wait-ms`
- `addons.synthesis.authority-tier.blog`
- `addons.synthesis.authority-tier.edu`
- `addons.synthesis.authority-tier.gov`
- `addons.synthesis.authority-tier.journal`
- `addons.synthesis.authority-tier.news`
- `addons.synthesis.min-bytes-per-item`
- `addons.synthesis.moe-mix`
- `addons.vector.top-k-default`
- `addons.web.enabled`
- `addons.web.top-k-default`
- `agents.0.id`
- `agents.0.merge.conflict`
- `agents.0.merge.order.0`
- `agents.0.merge.order.1`
- `agents.0.output.encoding`
- `agents.0.output.path`
- `agents.0.system`
- `agents.0.traits.0`
- `agents.0.traits.1`
- `alias.corrector.enabled`
- `aliases.cheap`
- `aliases.vision`
- `allocator.k_total`
- `allocator.mode`
- `allocator.temp`
- `allow.0`
- `allow.1`
- `allow.2`
- `allow.3`
- `allow.4`
- `allow.5`
- `audit.banner`
- `axes.evidence.0`
- `axes.evidence.1`
- `axes.evidence.2`
- `axes.freshness.0`
- `axes.freshness.1`
- `axes.freshness.2`
- `bindings.default`
- `bindings.moe`
- `budget.cancel_signal_propagation`
- `budget.time_ms`
- `cache.singleflight.enabled`
- `cache.singleflight.maxWaitMs`
- `calibration.mode`
- `chain.0`
- `chain.1`
- `chain.2`
- `chain.3`
- `chain.4`
- `chain.5`
- `chain.6`
- `concurrency.reranker_max_parallel`
- `concurrency.web_probe_parallel`
- `constraints.onnxMaxConcurrency`
- `constraints.timeBudgetMs`
- `context.matrix-transformer.enabled`
- `context.portfolio.enabled`
- `context.portfolio.weights.augmentability`
- `context.portfolio.weights.authority`
- `context.portfolio.weights.novelty`
- `correction.alias.enabled`
- `correction.alias.max-candidates`
- `correction.alias.merge`
- `correction.alias.threshold`
- `correction.alias.tile.dir`
- `critic.backoff-ms`
- `critic.enabled`
- `critic.max-retries`
- `critic_loop.max_attempts`
- `critic_loop.strategies.0`
- `critic_loop.strategies.1`
- `critic_loop.strategies.2`
- `deny.0`
- `desc`
- `description`
- `diag.nn.gradient.alpha`
- `diag.nn.gradient.beta`
- `diag.nn.gradient.enabled`
- `diag.nn.gradient.eps`
- `diag.nn.gradient.vanish-threshold`
- `diversity.dpp.enabled`
- `diversity.dpp.k`
- `diversity.dpp.lambda`
- `embedding.cache.enabled`
- `embedding.cache.max-entries`
- `embedding.cache.ttl-seconds`
- `enable.dpp`
- `enable.ocr`
- `evolver.ab-test-percent`
- `evolver.enabled`
- `expansion.extreme_z`
- `expansion.query_burst`
- `expansion.self_ask`
- `external.gemini.api-key`
- `external.gemini.enabled`
- `external.gemini.timeout-ms`
- `external.image.enabled`
- `external.image.provider`
- `external.image.timeout-ms`
- `external.translate.enabled`
- `external.translate.timeout-ms`
- `extracted_utc`
- `extremez.enabled`


## 4) 코멘트 정책
- 각 클래스 상단에 역할(Role), 기능 태그, 연결 포인트, 궤적 추정 힌트를 **Javadoc** 형식으로 삽입.
- 컨트롤러는 가능한 한 엔드포인트(HTTP Method/Path)를 발췌.
- 변경 이력은 리포지토리 내 `MERGELOG_*`, `PATCH_NOTES_*`, `CHANGELOG_*` 문서를 참조.

> 본 문서와 주석은 **문서화 전용**이며 실행 로직을 변경하지 않습니다.
