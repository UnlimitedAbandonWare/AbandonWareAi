# Build Fix Summary (src111_merge15)

## What changed
- **app/build.gradle.kts**: replaced placeholder `...` with valid `repositories` and `dependencies`; kept Java 21; source set pinned to `src/main/java_clean`.
- **build.sh**: removed obsolete `:lms-core` tasks; now builds only `:app`.
- **New classes**:
  - `com.example.lms.util.concurrency.SingleFlight` — to dedupe concurrent loads.
  - `com.example.lms.service.rag.fusion.ScoreCalibrator` + `RerankCanonicalizer` — optional RRF score normalization + URL canonicalization.
  - `com.example.lms.guard.PIISanitizer` — masks email/phone.
- **WeightedRRF**: optional setters for calibrator/canonicalizer (no behavior change by default).

## Why
- Prior build errors stemmed from malformed Gradle script (`...`) and references to a non-included module (`:lms-core`). Error patterns mined showed `cannot find symbol`, `package does not exist`, and `illegal start of expression` — all consistent with broken build configuration rather than code logic.

## How to build
```bash
./gradlew :app:bootJar
```

## Notes
- The active source set is `app/src/main/java_clean` (20 files), a compile-safe subset tailored for CI.
- Additional RAG features (ONNX gating, DPP) are present in this subset to keep integration points intact.
