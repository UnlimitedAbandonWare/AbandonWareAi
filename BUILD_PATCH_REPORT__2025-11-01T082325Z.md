# BUILD PATCH REPORT — src111_merge15  
*Generated:* 2025-11-01T082325Z

## Detected top error patterns
- **java.cannot_find_symbol**: 1201
- **java.package_does_not_exist**: 81
- **gradle.build_failed**: 55
- **java.incompatible_types**: 47
- **java.illegal_escape_character**: 4
- **gradle.dependency_resolution**: 2
- **java.class_interface_expected**: 0
- **maven.build_failure**: 0

## Patches applied (clean layer)
- Added `RetrievalOrderService` with risk‑softmax K allocator (A5).
- Added `SingleFlight` cache to suppress duplicate concurrent fetches (A7).
- Added `ExecutionBudget` and `OnnxCrossEncoderReranker#rerankWithBudget` (A1+A6).
- Extended `RerankCanonicalizer` with URL canonicalization removing tracking params (A3).
- Added `PIISanitizer` and wired config keys for preflight gate (A8).
- Ensured `application.yml` contains WPM fusion and budget/canonicalize toggles (A4+A6).

## Notes
- Clean build sources are under `app/src/main/java_clean` and are dependency‑light by design.
