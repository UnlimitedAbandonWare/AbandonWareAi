# Build Fix — Pattern: IllegalStartOfType → OnnxCrossEncoderReranker

**Problem**  
`illegal start of expression`/`illegal start of type` around `resolveBudgetMs(...)` in multiple
`OnnxCrossEncoderReranker.java` variants. Root cause: stray tokens (`...`) and brace imbalance from
partial snippets caused methods to appear inside expression contexts.

**Fix**  
Rewrote the three variants as compilable stubs with minimal interfaces and concurrency guard:

- `src/main/java/service/onnx/OnnxCrossEncoderReranker.java` — fallback `score()` with semaphore + time budget
- `src/main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java` — identity `rerankTopK(...)`
- `src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java` — implements interface, returns topN

This aligns with prior pattern remediation logged under `BUILD_PATTERN_SUMMARY.md` (IllegalStartOfType).
