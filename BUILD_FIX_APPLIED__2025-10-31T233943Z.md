# BUILD FIX APPLIED — src111_merge15
Timestamp (UTC): 2025-10-31T233943Z

## Changes
- **application.yml**: appended merge15 patch keys (fusion.calib/tail/cvar, rerank.dpp, gate.final/citation, extremez, overdrive).
- **BodeClamp.java**: added (`app/src/main/java_clean/com/example/lms/service/rag/fusion/BodeClamp.java`).
- **PlattIsotonicCalibrator.java**: added (`app/src/main/java_clean/com/example/lms/service/rag/fusion/PlattIsotonicCalibrator.java`).

## Validations (static)
- Source set uses `java_clean` only (app/build.gradle.kts) — isolates from legacy stubs.
- `WeightedRRF` uses **ScoreCalibrator** + **RerankCanonicalizer** (java_clean).
- `OnnxCrossEncoderReranker` includes a **Semaphore gate** and **timeout fallback** (java_clean).
- **DppDiversityReranker**, **FinalSigmoidGate**, **CitationGate** present in java_clean.
- Gradle wrapper scripts present and standard (`gradlew`, `gradle/wrapper/*`).

## Notes
- No remaining **regex illegal escape** patterns detected in `.java` files (scan: 0 hits).
- Historical error `cannot find symbol Bm25LocalIndex` avoided in `app` by not depending on local BM25 in `java_clean`.
