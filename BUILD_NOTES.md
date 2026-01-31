# Build Patch Notes — src111_merge15

## Changes Applied

- Added stub for `com.nova.protocol.fusion.NovaNextFusionService` with nested `ScoredResult` and `fuse` method.

- Deduplicated `@Autowired` fields `RiskKAllocator kalloc` and `NovaNextProperties nprops` in:

- In `WeightedRRF`, removed `static` modifier from methods that accessed the instance field `hypernovaBridge` to avoid static context reference errors.

- In `RrfHypernovaBridge`, added `postProcess(List<Double>)` helper to align with call sites returning a `List` instead of a `ContextSlice`.

- Fixed missing parenthesis in `RuleBreakInterceptor` call to `HmacSigner.verifyAndDecode`.


## Found build-error pattern logs

- `BUILD_FIX_NOTES.md` (contains compile error traces)

- `BUILD_FIX_REPORT.md` (contains compile error traces)

- `BUILD_FIX_NOTES__79_fusion.md` (contains compile error traces)

- `build_error_patterns_summary.json` (contains compile error traces)

- `PATCH_REPORT__nine_tile_alias_corrector.json` (contains compile error traces)

- `HYPERNOVA_PATCH_REPORT.json` (contains compile error traces)

- `scripts/analyze_build_output.py` (contains compile error traces)


---
See AUTO_PATTERN_APPLY_REPORT.md for the pattern scan and fix mapping.