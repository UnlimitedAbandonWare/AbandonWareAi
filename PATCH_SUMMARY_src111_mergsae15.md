# PATCH SUMMARY — src111_mergsae15

## What I fixed
- **YAML Duplicate Key**: Normalized `src/main/resources/application.yml` to remove duplicate top-level sections (`spring`, `rag`, `naver`, `rerank`, `guard`, `probe`, `retriever`, `upstash`, `cache`, `fusion`). Multi-document separators (`---`) at column 0 were removed to avoid SnakeYAML `DuplicateKeyException`/`ComposerError` on boot.
- **Safe merge**: Deep-merged repeated sections so later values override earlier ones; preserved all unique child keys.
- **Defaults hardening**: Inserted (only-if-missing) the following knobs aligned with Jammini inventory:
  - `rag.dpp.{enabled:true,k:12,alpha:0.65}`
  - `fusion.{calibrator:isotonic, normalizer:mp-law, strategy:wpm, wpm.p:1.7, cvar.alpha:0.1}`
  - `score.weights.{authority:0.6, recency:0.4}`, `score.recency.lambda:0.015`
  - `budget.total-ms:3500`, `plans.active:safe_autorun.v1`
  - `probe.search.enabled:true`

## New compile-safe utilities (added)
- `service/rag/fusion/WpmFuser.java` — weighted power mean combiner.
- `service/rag/fusion/TailWeightedPowerMean.java` — tail-α head selection + WPM(p).
- `observability/TracingAspect.java` — minimal AOP tracer (logs ms latency @ DEBUG). No Micrometer import (compile-safe).

## Build-error pattern tracker (update)
- Added detection to pattern store: `DUP_YAML_DUP_KEY`  
  - **Regex:** `DuplicateKeyException.*duplicate key\s+(?<key>[A-Za-z0-9_.-]+)`  
  - **Fix map:** `normalize_yaml: application.yml`.
- Recorded user-provided failure with key=`rag` and file=`application.yml` and timestamp.

Artifacts:
- `_patch_artifacts/build_error_patterns.json` (merged) — includes `DUP_YAML_DUP_KEY` with the above regex.
- `BUILD_PATTERN_REPORT__20251021.md` — this run’s notes.

## Notes
- The workspace did **not** include a file literally named `src111_merge15 - 2025-10-21T140155.670.zip`. I applied the same analysis/patch to the closest available bundle: `src111_merge215 (2).zip`, keeping paths and module layout intact.
- No source files were removed. New files are additive and reside under `com.abandonware.ai` to avoid package clashes.
