# PATCH NOTES — src111_merge16 (22)

- Added **BM25** retriever + handler + Spring config under `app/src/main/src/main/java/com/example/lms/service`.
- Patched **OnnxCrossEncoderReranker** with budget-aware guard if marker `/* RERANK_START */` exists.
- Patched **FusionCalibrator** to include a minimal monotonic isotonic mapping.
- Created `src/main/resources/application-features-example.yml` feature toggles.

This patch is dependency-free; wiring points are conservative and safe to ignore in builds where the target classes are absent.
