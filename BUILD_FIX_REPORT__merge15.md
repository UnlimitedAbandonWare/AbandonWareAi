# Build Fix Report (merge15)

*Generated:* 2025-10-15T13:36:43.883172Z

## Applied Config & Code Patches
- Appended advanced orchestration configs to `app/src/main/resources/application.yml`.
- Added Plan DSL YAMLs under `app/src/main/resources/plans/`.
- Introduced stub implementations for Budget, ONNX semaphore guard, Single-Flight, DPP, Score Calibrators, Planner Nexus, Context Hygiene, MCP, Telemetry, and Learning.
- Renamed `RuleBreakInterceptor` bean in app module to `novaRuleBreakInterceptor`.

## Historical Build Error Patterns (from repo telemetry)
- Lombok/Logger symbol errors, Bean name conflicts, YAML DuplicateKeyException, One-public-type-per-file violations (OCR models), Wrong imports in FinalQualityGate.
- See `BUILD_FIX_NOTES.md` and `analysis/*` for details.
