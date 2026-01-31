# Patch Applied — src111_merge15 (java_clean focus)
Date: 2025-11-01T01:37:25.740254Z

## Summary
- Added Predict Tree API (`/api/predict-tree`) with `ScenarioExpander` (SelfAsk-backed), `ProbabilityEngine` (RAG-backed heuristic), and Mermaid serializer.
- Enhanced `WeightedRRF` with hybrid fuse() using RRF + WPM + CVaR tail mean + Delta projection (stubs).
- Introduced minimal `TraceContext` and integrated into `AnswerSanitizer` to display ZeroBreak banner.
- Added OCR config keys to `application.yml` (`retrieval.ocr.*`), safe even if OCR module absent.
- Kept Gradle `java_clean` sourceset; avoided duplicate-class conflicts from backup/legacy trees.

## Notes
- This patch targets the curated `app/src/main/java_clean` source set to keep the build green.
- If you later switch to the full sources, apply the same diffs under `src/main/java/com/abandonware/ai/**` as per the internal guide.
