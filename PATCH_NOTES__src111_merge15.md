
# PATCH NOTES — src111_merge15 (auto-patch)

Timestamp (UTC): 2025-10-29T20:57:42.330137Z

## Summary
Applied targeted refactors to stabilize Gradle multi-module build and align with plan DSL + production defaults.
Key actions:
- Toolchain unified to **Java 21** (root + cfvm-raw).
- **Module map** expanded in `settings.gradle` to include `cfvm-raw`, `tools-scorecard` (present).
- **Duplicate plan YAMLs** removed/normalized; loader glob narrowed.
- **Source set hygiene**: excluded backup folders and unintended root `src/` from app compilation.
- **Prod defaults** hardened: probe disabled by default, official sources only, vector+ONNX enabled, Upstash TTL normalized, Naver hedge/timeouts set, OCR disabled in prod.
- Build-error miner artifacts consolidated under `analysis/`.

## Changeset
- DELETE app/src/main/resources/plans/hyper_nova.v1.yaml  # duplicate; keep hypernova.v2.yaml as canonical
- DELETE app/src/main/resources/plans/zerobreak.v1.yaml  # duplicate; keep zero_break.v1.yaml
- DELETE app/src/main/resources/plans/brave.yaml  # duplicate; keep brave.v1.yaml
- DELETE app/src/main/resources/plans/safe_autorun.yaml  # duplicate; keep safe_autorun.v1.yaml


### Java toolchain patches
- build.gradle.kts; cfvm-raw/build.gradle.kts

### Plan loader scan narrowing
- Patched 5 loader(s) to use: `plans/{default,brave,rulebreak,zero_break,hypernova}*.yaml`

### Prod configuration
- application-prod.yml updated with conservative defaults

## Build-error patterns (from repo logs)
Top offenders and intended mitigations are captured in `analysis/build_error_patterns_consolidated.json` and `analysis/build_error_fix_suggestions.json`.
