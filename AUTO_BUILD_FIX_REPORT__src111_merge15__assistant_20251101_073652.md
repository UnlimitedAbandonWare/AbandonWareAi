# Auto Patch Report — src111_merge15 (assistant)

Date: 2025-11-01T07:36:52.011109

## Summary
Applied compile‑safe patches aligned with the "Easy wins" checklist, focusing on the active Gradle module **app** (which compiles sources from `src/main/java_clean`).

### Files added (java_clean)
- `com/example/lms/web/RuleBreakInterceptor.java` — header gate (X‑Brave‑Mode, X‑Zero‑Break‑Mode, X‑RuleBreak‑Token).
- `com/example/lms/web/WebConfig.java` — registers the interceptor via `WebMvcConfigurer`.
- `com/example/lms/service/rag/handler/OcrRetrievalHandler.java` — OCR handler stub producing standard context items.

### Files updated
- `app/src/main/resources/application.yml` — appended:
  - `reranker.onnx-*` settings (enabled, concurrency cap, acquire timeout, fallback).
  - `retrieval.ocr.enabled=true`
  - `rulebreak.*` flags.

## Build error patterns (from repo archive)
- `java.cannot_find_symbol`: 1201
- `java.package_does_not_exist`: 81
- `java.incompatible_types`: 47
- `gradle.build_failed`: 55

The active app module compiles against **java_clean** sources to avoid the above historical failures, while still exercising the retrieval/fusion/rerank pipeline with guards and stubs.

