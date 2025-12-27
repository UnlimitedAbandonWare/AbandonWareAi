# Auto Patch Report — src111_merge15

Date: 2025-11-01T05:23:40.021056Z

Applied changes:
- Added Overdrive/Extreme‑Z handlers (`service/rag/overdrive/*`) with safe heuristics and correct Java regex escapes.
- Patched `DynamicRetrievalHandlerChain` to call `maybeExpand(query)` and to expose DPP helper.
- Added `guard/AnswerGateChain` wiring `CitationGate` + `FinalSigmoidGate`.
- Kept existing `java_clean` build layout; no external dependencies added.

Build-error pattern remediation:
- **Illegal escape sequences** (e.g., `"\\s+"`, `"\\p{L}"`) are eliminated in new sources by using `"\\\\s+"`, `"\\\\p{L}"` when needed.
- **Bm25LocalIndex missing**: not referenced in `java_clean`; avoided accidental imports. Existing non-clean sources keep their logs isolated.

Runtime toggles (already present):
- `rerank.dpp.enabled=true`, `onnx.max.concurrent=4`, `onnx.acquire.timeout.ms=150`, `gate.citation.min=3`, `gate.final.pass=0.90`.

Files touched:
- `app/src/main/java_clean/com/example/lms/service/rag/overdrive/OverdriveGuard.java` (new)
- `app/src/main/java_clean/com/example/lms/service/rag/overdrive/AngerOverdriveNarrower.java` (new)
- `app/src/main/java_clean/com/example/lms/service/rag/overdrive/ExtremeZSystemHandler.java` (new)
- `app/src/main/java_clean/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java` (modified)
- `app/src/main/java_clean/com/example/lms/guard/AnswerGateChain.java` (new)
