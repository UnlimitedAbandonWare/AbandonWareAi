# Patch Report — src111_merge15 (by GPT-5 Pro)
Date: 2025-11-01T01:11:44

## Summary
- Ensured P0 features are wired in `app/src/main/java_clean`:
  - DPP Diversity Reranker
  - URL Canonicalizer + Duplicate Merge
  - ONNX Cross-Encoder semaphore gate + timeout
  - Single-Flight cache executor
  - Score Calibrator (Platt/Isotonic) and Bode Clamp
- Added Plan DSL sample: `resources/plans/safe_autorun.yaml`
- Hardened configuration toggles in `resources/application.yml`
- Reviewed build error patterns; current outstanding = 0 (per BUILD_ERROR_ANALYSIS.md)

## Build Error Patterns (last run)
See: BUILD_ERRORS_SUMMARY.md & BUILD_ERROR_ANALYSIS.md
Key historical patterns that were fixed in java_clean:
- WeightedRRF overload mismatch → simplified API + calibrator/canonicalizer hooks
- OnnxCrossEncoderReranker.rerankTopK missing → convenience overload added with semaphore guard
- Duplicate class packages (com.abandonware vs com.example) → app uses `java_clean` only

## Next Steps (optional)
- Wire `PlannerNexus.apply(plan)` to actually reorder handlers and K allocation.
- Add integration tests for `/api/probe/search` verifying lower redundancy and stable ranking.