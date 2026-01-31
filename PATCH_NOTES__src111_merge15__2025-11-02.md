# src111_merge15 — Patch Notes (2025-11-02)

Applied P0/P1 items based on Jammini Memory + Scaffold guides:

- **Fusion**
  - `WeightedRRF`: added *Mode=WPM* toggle, `p` exponent, external `ScoreCalibrator` + `RerankCanonicalizer` hooks; safe fallbacks kept.
  - New `RrfFusion` orchestrator to wire calibrator/canonicalizer and expose a simple `fuse` facade.

- **Diversity + ONNX gate**
  - `DppDiversityReranker`: already present; chain extended to include `DPP` → `ONNX`.
  - `OnnxCrossEncoderReranker`: implemented a concurrency gate (Semaphore) and *time‑budget* fallback via `TraceContext`.

- **Governance**
  - `PIISanitizer`, `CitationGate`: present; left as‑is.

- **Overdrive / Extreme‑Z (optional)**
  - Added `OverdriveGuard` (heuristic) + `ExtremeZSystemHandler` (burst query seeds).

- **Trace/Budget**
  - `TraceFilter`: reads `X-Deadline-Ms` and primes `TraceContext.startWithBudget()`.

- **Caches**
  - Added minimal `EmbeddingCache` (in‑mem, bounded).

- **Scaffold / Plan DSL**
  - `agent_scaffold` present; `plans/` contains `safe_autorun.v1.yaml`, `brave.v1.yaml`, `zero_break.v1.yaml`.

## Build‑error pattern assist

Used persisted pattern store:
- `java.cannot_find_symbol`, `java.package_does_not_exist`, `java.illegal_escape_character`, etc. (see BUILD_ERROR_PATTERNS.json).
- Applied safe, deterministic edits only:
  - Package/import order check (none found).
  - Ensured regex escapes (`\\p{L}` style) already correct in BM25.
  - Provided missing stubs and compile‑safe defaults for ONNX reranker and handlers.

> See `AUTO_PATTERN_APPLY_REPORT.md` and `BUILD_ERROR_PATTERN_SUMMARY.md` for historical counts.

## Toggles

- `fusion.mode` (RRF|WPM), `fusion.wpm.p` (default 1.0)
- `gate.minCitations` (default 3)
- `onnx.maxInFlight` (default 4)

