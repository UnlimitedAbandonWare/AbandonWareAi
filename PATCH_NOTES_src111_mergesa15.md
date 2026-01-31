# PATCH_NOTES — src111_mergesa15

**Target:** fix Gradle compile error

```
> Task :compileJava FAILED
... OnnxCrossEncoderReranker.java:41: error: illegal start of expression
    public java.util.List<com.abandonware.ai.service.rag.model.ContextSlice> rerankTopK(
    ^
1 error
```

## Root cause

`OnnxCrossEncoderReranker#rerank(String,List<ScoredDoc>)` method was missing a closing brace (`}`) after the `try/catch` block.  
As a result, the following method `rerankTopK(List<ContextSlice>, int)` was parsed **inside** the previous method scope, yielding *illegal start of expression*.

## Fix

- Inserted a single `}` to close `rerank(...)` right after the `catch` block.
- Verified brace balance for the compilation unit (now balanced: 0).
- Left functional logic unchanged, including:
  - `onnx.enabled` gate and `OnnxRuntimeService#isReady()` check.
  - Stable fallback `limitStable(...)` to keep original order when ONNX is disabled/not ready.
  - Tokenization via `TokenizerAdapter#encodePairs` and batch scoring.

**Patched file**

- `src/main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java`

## Why this is safe

- No public API signature change.
- The newly added brace only restores proper method boundaries.
- Unit callers such as `RerankOrchestrator` continue to call `onnx.rerankTopK(...)`.

## Build‑error pattern mining (repo‑internal)

Using the repository’s error‑pattern artifact `HYPERNOVA_PATCH_REPORT.json`, the historical counts observed:

[
  [
    "error: cannot find symbol",
    149
  ],
  [
    "symbol:\\s+class\\s+\\w+",
    104
  ],
  [
    "BUILD FAILED",
    17
  ],
  [
    "found:\\s+[\\w<>\\[\\],\\s]+",
    11
  ],
  [
    "required:\\s+[\\w<>\\[\\],\\s]+",
    9
  ],
  [
    "error: method does not override or implement a method from a supertype",
    9
  ],
  [
    "error: package [\\w\\.]+ does not exist",
    4
  ],
  [
    "error: incompatible types:",
    3
  ]
]

Examples are stored under `error_pattern_examples` in the same JSON.

## Sanity checks

- Found `ContextSlice` at `com.abandonware.ai.service.rag.model.ContextSlice`.
- Found `OnnxRuntimeService` at `com.abandonware.ai.service.onnx.OnnxRuntimeService`.
- Duplicated sample implementations under `com.example.lms` remain untouched (they are not referenced by the `demo-1` module path from the error log).

## Output

This archive is the patched tree ready to drop‑in:

- **/mnt/data/src111_mergesa15.zip**
