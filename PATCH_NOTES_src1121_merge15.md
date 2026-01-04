# PATCH_NOTES_src1121_merge15

## What I fixed
- **Build error** in `RerankOrchestrator`: missing symbol `OnnxCrossEncoderReranker.rerankTopK(List<ContextSlice>, int)`.
  - Implemented an overload **`rerankTopK(List<ContextSlice>, int)`** in
    `src/main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java`.
  - The overload uses a safe heuristic anchor (top item's title) and ONNX cross‑encoder
    scoring when available; otherwise **falls back** to stable top‑K trimming.
  - Tie‑breakers preserve the previous rank for stability.

## Why it happened
- Prior patches only provided `rerank(String, List<ScoredDoc>)` and **did not match**
  the orchestrator’s expected API (`rerankTopK(List<ContextSlice>, int)`), producing
  the `cannot find symbol` compiler error during `:compileJava`.

## Behavior
- If `onnx.enabled` and model/tokenizer are ready → tokenizes pairs `(anchor, title+snippet)`,
  runs ONNX session, and sorts by logits **desc**; returns top‑K.
- If not ready → simply returns the **head K** of the incoming list (bi‑encoder result).

