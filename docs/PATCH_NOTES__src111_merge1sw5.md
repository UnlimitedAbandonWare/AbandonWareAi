# PATCH NOTES — src111_merge1sw5

## Why
`bootRun` crashed due to invalid boolean placeholder injected into `SearchProbeController`:
a literal `{{probe.search.enabled:false}}` string was being coerced to boolean.

## What changed
### Fixed placeholders
- `src/main/java/com/example/lms/probe/SearchProbeController.java`
  * `@Value("{{probe.search.enabled:false}}")` → `@Value("${{probe.search.enabled:false}}")`
  * Rewrote the class to a minimal, valid controller with explicit constructor injection.
- `src/main/java/com/abandonware/ai/agent/integrations/ScoreCalibrator.java`
- `src/main/java/com/example/lms/api/N8nWebhookController.java`
  * Normalized any `@Value("{{...}}")` → `@Value("${{...}}")` that were missing `$`.

### Added
- `src/main/java/com/example/lms/probe/ProbeProperties.java` (optional, not required by the controller but ready for future typed binding)
- `tools/error_pattern_extractor.py` (log scanner → `build-logs/error_patterns.json`)
- `docs/BUILD_ERRORS.md` (root cause, fix, verification steps)
- `build-logs/error_patterns.json` (extracted patterns from the provided bootRun log)

## Diff manifest
```json
{
  "added": [
    "build-logs/2025-10-18-bootRun.log",
    "build-logs/error_patterns.json",
    "docs/BUILD_ERRORS.md",
    "patches/patch_log.json",
    "src/main/java/com/example/lms/probe/ProbeProperties.java",
    "tools/error_pattern_extractor.py"
  ],
  "removed": [],
  "changed": [
    "src/main/java/com/abandonware/ai/agent/integrations/ScoreCalibrator.java",
    "src/main/java/com/abandonware/ai/probe/SearchProbeController.java",
    "src/main/java/com/example/lms/api/N8nWebhookController.java",
    "src/main/java/com/example/lms/probe/SearchProbeController.java"
  ]
}
```

## How to verify
1. `./gradlew bootRun` (or `gradlew.bat bootRun` on Windows)
2. Expect **no** `Invalid boolean value` error.
3. Probe endpoint behavior:
   - Default: disabled (`404` for `/api/probe/search`).
   - Enable with environment: `PROBE_SEARCH_ENABLED=true` (and set `PROBE_ADMIN_TOKEN`), then try:
     ```bash
     curl -X POST http://localhost:8080/api/probe/search           -H "X-Probe-Token: $PROBE_ADMIN_TOKEN"           -H "Content-Type: application/json"           -d '{"intent":"smoke","useWeb":false,"useRag":false}'
     ```

## Safety
- No secrets committed; all tokens read from env with safe defaults.
- Controller continues to require `X-Probe-Token` when enabled.
