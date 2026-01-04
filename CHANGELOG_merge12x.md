# CHANGELOG — src111_merge12x

Date: 2025-10-11

Added (flagged OFF by default):

- `telemetry/MatrixTelemetryExtractor.java`, `telemetry/VirtualPointService.java`
- `alias/TileDictionaries.java`, `alias/TileAliasCorrector.java`
- `mpc/MpcPreprocessor.java`, `mpc/NoopMpcPreprocessor.java`
- `src/main/resources/application-features-example.yml`
- `docs/PR*_*.md` (3 PR guides)

Integration Notes:
- All modules are pure Java and fail-soft.
- No compile/run-time coupling with existing beans is required.
- See docs/ for wiring snippets.