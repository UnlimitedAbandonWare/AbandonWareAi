# PATCH — src111_merge127

Applied 2025-10-10T23:40:04.131897

**What was added (build-safe, default OFF):**
- `telemetry/MatrixTelemetryExtractor.java` — derive M1..M9 matrices from generic run map
- `telemetry/VirtualPointService.java` — compose feature vector & append to NDJSON (no external deps)
- `mpc/MpcPreprocessor.java` + `mpc/NoopMpcPreprocessor.java` — 3D preproc hook (no-op)
- `alias/TileAliasCorrector.java` + `alias/TileDictionaries.java` — context-aware overlay corrector
- `src/main/resources/application-features-example.yml` — flags

**Why these:**
Repository already contains most modules (FlowJoiner, ExtremeZ, Overdrive, RuleBreak, Outbox, PII, RRF, ONNX, etc.).
Missing or diluted items were implemented with fail-soft defaults.

**How to enable:**
- Add to your `application.yml` (or import the example):
```
features:
  telemetry.virtual-point.enabled: true
  mpc.enabled: true
  alias.corrector.enabled: true
```
- (Optional) Use `VirtualPointService.appendNdjson(...)` where you finalize a run.

**No external dependencies added.**
