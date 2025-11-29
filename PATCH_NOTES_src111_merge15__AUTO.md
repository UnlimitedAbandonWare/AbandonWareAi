# PATCH NOTES — src111_merge15 (auto)

Applied targeted build-fix and A–E bundle essentials:

1) **Build wrapper error** — confirmed wrapper present and self-healing `gradlew` does not call `gradlew-real`.
2) **Fix illegal-start/class-expected** — corrected brace structure and stray tokens in:
   - `app/src/main/java/com/nova/protocol/alloc/RiskKAllocator.java` (moved method inside class; removed ellipsis).
3) **CFVM build-pattern extractor** — rewrote minimal, compilable versions:
   - `cfvm-raw/src/main/java/com/example/lms/cfvm/RawSlot.java` (Lombok `@Builder` record).
   - `cfvm-raw/src/main/java/com/example/lms/cfvm/BuildLogSlotExtractor.java` (regex updated to capture `expression|type`).
4) **Observability dependencies** — appended Micrometer/Prometheus and OpenTelemetry (OTLP) plus Mockito to `app/build.gradle.kts`.

Recommend: run `./gradlew :cfvm-raw:build :app:build` and verify `DuplicateClass` checkers pass. 
