# Change Log for src54core

## v54t (2025-08-22)

### ONNX Reranker Integration and Architectural Modularisation

**Scope/Motivation**

This release focuses on removing external API dependencies and enabling a
high‑performance local retrieval‑augmented generation (RAG) chatbot prototype.
Key objectives included integrating a local ONNX cross‑encoder reranker,
unifying duplicate `ModelRouter` beans, splitting responsibilities out of
`ChatService` into dedicated components, adding health indicators and
telemetry, and improving testability and CI coverage.

**Key Changes**

* **ONNX Runtime Support**: Implemented `OnnxRuntimeService` with support for
  loading a cross‑encoder ONNX model from `classpath:/models/your-cross-encoder.onnx`.
  Added configurable vocabulary path, max sequence length and a sigmoid
  normalisation flag.  The service now performs inference via
  `OrtSession.run()` when available and falls back to a Jaccard similarity
  when not.
* **Health Checks**: Added `OnnxRerankerHealthIndicator` which checks that the
  ONNX model initialises and performs a simple self‑test on startup.  The
  health status is exposed under `reranker.onnx` in the actuator.
* **Reranker Selection**: Introduced `RerankerSelector` to centralise
  selection of cross‑encoder reranker implementations based on
  `abandonware.reranker.backend`.  It falls back gracefully when the
  configured backend is not available.
* **Prompt/Streaming/Guard Orchestration**: Extracted `PromptOrchestrator`,
  `StreamingCoordinator` and `GuardPipeline` classes from `ChatService` to
  improve separation of concerns and testability.
* **Router Unification**: Marked `ModelRouterCore` as `@Primary` and removed
  `@Component` from `ModelRouterAdapter`, ensuring that only one router bean
  is injected when the legacy profile is disabled.
* **Configuration**: Extended `application.yml` with a nested
  `abandonware.reranker.onnx` section for model, vocabulary and normalisation
  settings.  Added default threshold values for mixture‑of‑experts routing.
* **Tests & CI**: Added integration tests `OnnxRerankerIT` and
  `BackendSelectionIT` to verify ONNX inference and reranker selection.
  Enabled Jacoco coverage reports and introduced a version purity check to
  ensure all `dev.langchain4j` artifacts resolve to version 1.0.1.

**Risk/Rollback**

* The ONNX model is loaded at runtime; if the file is missing or corrupt the
  application will fall back to lexical scoring and the health indicator will
  report `DOWN`.  In such cases revert the `backend` property to
  `embedding-model` or restore a working model file.
* Modularisation introduces new Spring beans; ensure that component scanning
  picks them up.  Rolling back simply involves removing the new classes and
  restoring the previous `ChatService` logic.


## v54s (2025-08-22)

### Hotfix: SSE cancellation propagation

**범위/동기**

* Streaming API의 SSE(Server‑Sent Events) 구현에서 클라이언트가 브라우저 탭을 닫거나 새로고침하여 스트림을 중간에 끊었을 때 서버 측에서 `IllegalStateException`이 발생하는 문제가 보고되었습니다. 이는 `ChatApiController#chatStream` 메서드에서 SSE 플럭스와 배경 작업 간의 생명주기가 분리되어, 취소 요청 이후에도 끊긴 스트림에 계속 데이터를 쓰려는 시도가 이뤄졌기 때문입니다.

**주요 변경사항**

* `ChatApiController#chatStream` 메서드에 취소 전파 로직을 추가했습니다.
  * `AtomicReference`로 현재 세션 ID와 배경 작업 `Disposable`을 추적하여 취소 시 세션 취소, sink 해제 및 배경 작업 dispose가 이루어지도록 했습니다.
  * SSE 구독이 취소될 때(`doOnCancel`) `chatService.cancelSession(...)`을 호출하여 LLM/웹 검색 작업을 즉시 중단하고, 등록된 sink를 해제하며 `Disposable.dispose()`를 호출하여 배경 스레드를 종료합니다. 또한 스트림을 `sink.tryEmitComplete()`로 종료합니다.
  * `doFinally`에서 취소·완료·오류 등 모든 종료 경로에서 sink 해제를 재확인하여 리소스 누수를 방지했습니다.
* `Mono.fromRunnable(...)` 호출의 반환값을 변수에 저장하여 `Disposable` 관리가 가능하도록 수정했습니다.
* `build.gradle`나 서비스 로직에는 변경이 없으며, 기존 검색/RAG/LLM 호출 흐름은 그대로 유지됩니다.

**위험**

* 취소 처리 시점에 경쟁 조건이 발생할 수 있으나, `try/catch`로 감싸고 중복 정리에 대한 영향을 최소화했습니다. 부작용은 로그 출력 정도에 그칠 것으로 예상합니다.

**롤백**

* 이 변경은 단일 커밋으로 구현되어 있으므로 문제가 발생할 경우 해당 커밋을 되돌리면 이전 동작으로 복구할 수 있습니다. 데이터 마이그레이션이나 스키마 변경이 없으므로 롤백이 간단합니다.

## v54r (2025-08-19)

This release integrates the “강화식” specifications A1–A3, B1–B4 and C1–C3 into the
existing src54 codebase to form src54core. Major highlights include:

* **Path conformity scoring**: Added `PathAlignedScorer` and wired it into
  `ContextualScorer` to multiply contextual scores based on alignment between
  predicted decision paths and historical trajectories. The multiplier ranges
  from 0.5 (misaligned) to 9.0 (perfectly aligned) and is controlled by
  `scoring.path-alignment.enabled` in `application.yml`.
* **Persistent path memory**: Extended `PersistentChatMemory` with
  `addPath()` and `pathHistory()` methods to store and retrieve user
  traversal paths separately from conversation messages.
* **Neural path reinforcement**: Introduced `ReinforcedPath` entity and
  `NeuralPathFormationService` to persist navigation paths when consistency
  scores exceed a threshold. A new `ReinforcedPathRepository` supports
  persistence, and `HybridRetriever` now triggers reinforcement after
  synergy scoring.
* **Configuration updates**: Added a `scoring.path-alignment.enabled` section to
  `application.yml` with a default value of `true`.
* **Tests**: Added unit tests for `ContextualScorer` and
  `NeuralPathFormationService` to verify path alignment boosts/penalises
  scores and that path reinforcement triggers correctly.
* **Version purity check**: Added a Gradle task `versionPurityCheck` to
  enforce that all `dev.langchain4j` dependencies resolve to version 1.0.1.
  The build fails fast and prints the conflicting coordinates when a 0.2.x
  module is detected.
