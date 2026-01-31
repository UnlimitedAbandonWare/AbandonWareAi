# PATCH NOTES — src111_mergswe15

**Goal:** Fix Gradle compile errors from `WeightedRRF` signature mismatches and missing method on `OnnxCrossEncoderReranker`. Also record the error patterns into the in-repo build-pattern memory.

## What I changed

### 1) WeightedRRF — rich overloads added
- **File:** `src/main/java/com/abandonware/ai/service/rag/fusion/WeightedRRF.java`
- **Added:**
  - `Map<String, ContextSlice> fuse(List<List<ContextSlice>> sources, int k, Map<String,Double> weights, ScoreCalibrator calibrator, boolean dedupe)`
  - `static List<Map<String,Object>> fuse(List<List<Map<String,Object>>> perBranch, int topK)` (legacy Self-Ask convenience)
- **Why:** Call sites in:
  - `com.abandonware.ai.agent.service.rag.fusion.FusionService`
  - `com.abandonware.ai.service.rag.fusion.RrfFusion`
  - `service.rag.selfask.SelfAskPlanner`
  expected these overloads. Previous class only exposed a numeric `fuse(double,double,double)`.

- **Impl highlights:**
  - Rank-based RRF term `1/(k+rank)` multiplied by per-source weight (if provided).
  - Optional `ScoreCalibrator.normalize(rawScore, source)` → mapped to a gentle `[0.75,1.25]` multiplier.
  - Dedup by `id` and keep the most informative representative slice (longer title+snippet).
  - Fused score clamped to `[0,1]` (tanh and clamp).

### 2) WeightedRRF in legacy packages
- **Files:**
  - `src/main/java/com/example/rag/fusion/WeightedRRF.java`
  - `src/main/java/service/rag/fusion/WeightedRRF.java`
- **Added:** same static list-of-map `fuse(...)` overload to satisfy legacy Self‑Ask callers.

### 3) OnnxCrossEncoderReranker — convenience method
- **File:** `src/main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java`
- **Added:** `List<ContextSlice> rerankTopK(List<ContextSlice> items, int topN)`
- **Behavior:** sorts by `ContextSlice.getScore()` descending with a small semaphore guard. If a full cross-encoder model is wired later, this method can call the generic `rerank(query, items, scorer, topN)`.

## Pattern memory updated (for the in-repo BUILD pattern system)

- **File updated:** `AUTO_PATTERN_APPLY_REPORT.md`
  - Added:
    - `method_cannot_be_applied_to_given_types__WeightedRRF_fuse` → fixed by adding overloads (3 occurrences).
    - `cannot_find_symbol__OnnxCrossEncoderReranker.rerankTopK` → fixed by adding convenience method (1 occurrence).

- **File updated:** `analysis/build_patterns_aggregated.json`
  - Appended pattern codes:
    - `OverloadMismatch` (WeightedRRF)
    - `MissingMethod` (OnnxCrossEncoderReranker)

This keeps a durable memory of the failure modes for future automatic remediation.

## Suggested follow‑ups (optional)
- If you want calibrator‑aware fusion parity with the “addons” module,
  consider porting `app/.../CalibratedRrfFusion` into the service package and unifying the interface.
- Add unit tests for:
  - equal‑weight RRF,
  - weight map application (`web=2.0` etc.),
  - calibration multiplier bounds,
  - stable dedupe by `id`.
