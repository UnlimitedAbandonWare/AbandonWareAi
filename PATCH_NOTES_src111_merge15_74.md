# PATCH_NOTES_src111_merge15_74.md

This patch implements the minimal evidence line‑up requested in the v0.2 spec:

- Retrieval: **BM25 local retriever** (stubbed, guarded by `retrieval.bm25.enabled=false`), basic config class.
- Scoring: **Isotonic calibrator** + loader and light touch integration into the ONNX reranker.
- Planning: **QueryComplexityClassifier**, **3‑way Self‑Ask planner** (BQ/ER/RC), **Plan‑DSL loader (stub)**.
- Orchestration: **ExpertRouter (K‑allocation light)**.
- Resilience: **BudgetContext**.
- Safety: **PII Sanitizer**, **CitationGate**, **FinalSigmoidGate**.
- Probe/Soak: **DTO expansion** for new request/response fields.

All new features ship **OFF by default** via System properties; they are safe to keep in production code paths.

> Edited files:
- src/main/java/com/example/lms/probe/dto/ProbeRequest.java
- src/main/java/com/example/lms/probe/dto/ProbeResult.java
- src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java (patched)

> Added files:
- src/main/java/com/example/lms/service/rag/retriever/Bm25LocalRetriever.java
- src/main/java/com/example/lms/config/Bm25Config.java
- src/main/java/com/example/lms/service/rag/scoring/IsotonicCalibrator.java
- src/main/java/com/example/lms/service/rag/scoring/CalibrationModelLoader.java
- src/main/java/com/example/lms/service/rag/qc/QueryComplexityClassifier.java
- src/main/java/com/example/lms/service/rag/selfask/Branch.java
- src/main/java/com/example/lms/service/rag/selfask/SelfAskPlanner.java
- src/main/java/com/example/lms/service/rag/plan/PlanDslLoader.java
- src/main/java/com/example/lms/strategy/ExpertRouter.java
- src/main/java/com/example/lms/infra/time/BudgetContext.java
- src/main/java/com/example/lms/guard/PiiSanitizer.java
- src/main/java/com/example/lms/guard/CitationGate.java
- src/main/java/com/example/lms/guard/FinalSigmoidGate.java
- src/main/java/com/example/lms/config/FeatureFlags.java
- plans/recency_first.v1.yaml
- plans/kg_first.v1.yaml

## Notes

- The repository contains several placeholder classes with ellipses (`...`). The patch **does not** touch those files to avoid introducing build regressions.
- The ONNX reranker integration uses a safe no‑op calibration hook to keep dependencies minimal and avoid modifying opaque `Content` objects.
- Probe/Soak APIs are extended at the DTO level; the existing endpoints will accept/emit additional fields as JSON without changing business logic.

