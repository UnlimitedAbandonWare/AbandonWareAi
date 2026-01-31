# Auto Build-Fix Report — src111_merge15 (2025-11-01T204955Z)

## What I changed
1) **Deduplicated sources** — compile only curated `app/src/main/java_clean` to avoid duplicate classes between `src/main/java` and `app/src/main/java`.
2) **Root Gradle fix** — removed invalid `java { toolchain }` block at root (root project has no `java` plugin).
3) **Repositories & deps** — restored `repositories` block and added `spring-boot-starter-web` for controller compilation; kept `snakeyaml` for Plan‑DSL loader.
4) **Plan‑DSL resources** — ensured minimal `plans/*.yaml` exist (`safe.v1.yaml`, `brave.v1.yaml`, `zero_break.v1.yaml`) for `PlannerNexus` fallback.

## Why — mapped to error patterns
- `JavacDuplicateClass` / `java.duplicate_class` → fixed by **sourceSet narrowing**.
- `java.illegal_escape_character` → curated **java_clean** sources already normalized regex (e.g., `\s+`, `\p{L}` style).
- `java.cannot_find_symbol (Bm25LocalIndex)` → legacy BM25 impl not compiled; safe stub used instead.
- `GradleBuildFailed` (root `java` extension) → removed root toolchain block.

## Touch points
- `app/build.gradle.kts` — rewritten
- `build.gradle.kts` (root) — simplified aggregator
- `src/main/resources/plans/*.yaml` — added minimal defaults

## Next steps (optional)
- If you need ONNX at runtime, add `ai.onnxruntime:onnxruntime` and wire the real scorer in `OnnxCrossEncoderReranker`.
- If you need Lucene BM25 locally, add `org.apache.lucene:lucene-core` and friends, and re‑enable the BM25 module.

