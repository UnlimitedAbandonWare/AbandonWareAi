# src111_mergsae15 — CFVM-Raw patch bundle

This patch removes placeholder ellipses (`...` / `…`) that caused compilation errors
and replaces the `cfvm-raw` module with a minimal, fully compiling implementation:

- `RawSlot` / `RawSlotExtractor` — typed event slot and extractor interface.
- `BuildLogSlotExtractor` — recognizes common Gradle/Javac error patterns.
- `RawMatrixBuffer` — rolling buffer with a 3×3 Boltzmann-normalized weight matrix.
- `CfvmRawService` — orchestrates ingestion and exposes weights + recent error codes.

`cfvm-raw/build.gradle.kts` has been normalized (Java 17, SLF4J, JUnit5).

### How CFVM-Raw is used
1. `CfvmRawService.ingest(log)` parses build logs into slots.
2. A 3×3 matrix is updated with exponential decay (`fit(0.92)`).
3. `weights(T)` returns a temperature-scaled softmax over the nine virtual tiles.
4. `recentCodes(k)` returns MRU error tags for quick fix routing.

### Notes
- This bundle intentionally keeps dependencies minimal to avoid network resolution flakiness.
- If other modules still reference old `cfvm` classes, redirect them to `CfvmRawService`.