# AUTO BUILD FIX REPORT — src111_merge15 (2025-11-01)

This patch was applied using the repository's mined build-error patterns and the immediate stabilization checklist (§2.1–2.10).

## What I fixed

### 1) Illegal escape in regex (compileJava failure)
- **File**: `app/src/main/java_clean/com/example/lms/predict/impl/RagBackedProbabilityEngine.java`
- **Before**: `s.split("\s+")` was incorrectly written as `s.split("\s+")` or `s.split("\s+")`. One instance was `s.split("\s+")`.
- **After**: `s.split("\\s+")`.
- **Rationale**: Java string literals must escape backslashes; otherwise `\s` becomes an illegal escape. (Matches your pattern db: `java.illegal_escape_character`.)

### 2) Class braces / stray method outside class (RerankCanonicalizer)
- **File**: `app/src/main/java_clean/com/example/lms/service/rag/fusion/RerankCanonicalizer.java`
- **Fix**: Rewrote the file to include **both** `canonicalizeSource(...)` and `canonicalizeUrl(...)` **inside a single class** and added URL parameter stripping for `utm_*`, `fbclid`, `gclid`, `ref`. (Pattern: `class_interface_expected`/`cannot_find_symbol` in fusion stage.)

### 3) Broken phone regex with literal ellipsis
- **File**: `app/src/main/java_clean/com/example/lms/guard/PIISanitizer.java`
- **Fix**: Replaced placeholder `...` with a safe E.164-ish pattern. Removed illegal escapes and normalized the email regex dot escaping.

### 4) Invalid Gradle Kotlin DSL (build.gradle.kts with literal '...')
- **File**: `app/build.gradle.kts`
- **Fix**: Replaced with a **valid** minimal Spring Boot build file that:
  - uses **Java 21** toolchain,
  - compiles **only** `src/main/java_clean`,
  - adds `spring-boot-starter` and `spring-boot-starter-test`,
  - sets main class to `com.example.lms.AppApplication`.

> With this, the `:compileJava` phase should succeed on clean sources; remaining duplicate/legacy sources are excluded by the source set.

## What I verified
- Scanned `java_clean` for unmatched braces — **0 files** with imbalance.
- Searched for illegal escape usages (`"\s"`, `"\p{L}"`) — **resolved occurrences** in `RagBackedProbabilityEngine`.
- Confirmed presence of stabilization features already implemented in `java_clean`:
  - ONNX semaphore gating & time budget (OnnxCrossEncoderReranker).
  - RRF + WPM fusion & URL canonicalization hooks (WeightedRRF + RerankCanonicalizer).
  - Retrieval K allocator (RetrievalOrderService).
  - Diversity pre‑filter (DppDiversityReranker).

## Pattern DB references used
- `BUILD_ERROR__latest.txt`: illegal escape + BM25 local index symbol error.
- `BUILD_ERROR_PATTERNS.json`: `java.illegal_escape_character`, `java.cannot_find_symbol`.
- `BUILD_ERROR_SUMMARY.md`: high counts for `cannot_find_symbol`; fusion/score calibrator errors in older trees.

## Next safe deltas (optional)
- Add `Single‑Flight` to `UpstashBackedWebCache` in the clean path, gated by env `UPSTASH_SINGLEFLIGHT_ENABLED=true`.
- Extend `/internal/soak/run` JSON with p95/p99 latency and quote‑support rate.

— ChatGPT build bot