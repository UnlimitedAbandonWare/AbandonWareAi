# Patch notes — src111_msaerge15

- Replaced broken `AnalyzeWebSearchRetriever.java` (garbled merge tokens: `public \1`, `role: config`, `getDecl...`).
- Implemented clean `@Component` with constructor injection and safe fallbacks:
  - `SmartQueryPlanner` is optional; fallback to single query.
  - Preprocess via `QueryContextPreprocessor.enrich()`; empty → return `[]`.
  - Calls `NaverSearchService.searchSnippets(pq, webTopK)` and merges snippets in an ordered set then trims to `topK`.
- Added tuning setters: `setTimeoutMs(int)` and `setWebTopK(int)` (kept for compatibility with existing callers).
- Constructor parameters bind to properties:
  - `naver.search.web-top-k` (default 10)
  - `rag.analyze.top-k`   (default 6)
  - `naver.search.timeout-ms` (default 1800ms)
- This addresses Gradle `:compileJava` failures observed in the user's log.

Build-error fingerprints consulted:
- `BUILD_ERROR_PATTERNS.json` → ILLEGAL_ESCAPE_CHAR, BAD_HYPHEN_ESCAPE, SPLIT_RAW_WS; none apply to this fix.
- `BUILD_ERROR_PATTERNS.md`/`docs/BUILD_ERRORS.md` used to verify prior conventions for safe defaults.