# v12 Change Log

## Added
- SelfAskPlanner: `generateThreeLanes` with lanes BQ/ER/RC and `SubQuestion` type.
- OCR service package `com.example.lms.service.ocr` with `OcrService`, `BasicTesseractOcrService`, and model records.
- PRCYK Health scorer package `com.example.lms.agent.health` with `HealthSignals`, `HealthWeights`, `HealthScorer`.
- FlowJoiner: overloaded `sequence(signals, weights, degrade, fallback)` applying PRCYK gating.

## Config
- application.yml: added `agent.prcyk` and `rag.ocr` sections (feature flags & thresholds).

## Build
- Gradle: added Tess4J dependency `net.sourceforge.tess4j:tess4j:5.10.0` to app and lms-core.
