# AUTO BUILD FIX REPORT — src111_merge15

Applied: 2025-10-30T22:40:50.341555Z

## What I changed
- **Gradle (app/build.gradle.kts)** — replaced malformed placeholders (`...`) with valid Kotlin DSL; pinned to Java 21 and Spring Boot 3.3.3; compile only `src/main/java_clean`.
- **settings.gradle** — restricted to `:app` only to avoid compiling incomplete modules.
- **java_clean/** — replaced broken/incomplete stubs (with `...`) by compile-safe minimal implementations for:
  - DPP diversity reranker, score calibrator, power-mean fuser, RRF fuser canonicalization.
  - ONNX cross-encoder semaphore gate.
  - Single-Flight executor.
  - Query complexity classifier.
  - Safety gates (CitationGate, FinalSigmoidGate, PII/Answer sanitizers).
  - ProbeController + bootstrap endpoint.

## Error patterns used
- `GRADLE_KTS_PLACEHOLDER` — repositories/dependencies contained `...` → **fixed by writing valid sections**.
- `MISSING_SUBPROJECT_RESOLUTION` — app depended on `:lms-core` while root settings omitted it → **removed project dependency; keep `:app` only**.
- `JAVA_STUB_ELLIPSIS` — classes containing `...` or unbalanced braces → **rewritten with minimal valid code**.
- From existing pattern DB: `ILLEGAL_ESCAPE_CHAR`, `SPLIT_RAW_WS` → **ensured proper string escaping**.

## How to build
```bash
./gradlew :app:bootJar
# run
java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar
```

## Notes
- This is a conservative, compile-first patch. The minimal API endpoints `/bootstrap` and `/api/probe/search` will start.
- You can progressively re-enable full sources by switching `build.gradle.kts` to `src/main/java` once remaining modules are complete.
