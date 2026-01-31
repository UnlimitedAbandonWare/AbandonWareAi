# Patch3 — Build Error Recorder Enhancement (Hypernova-aligned)

This patch strengthens the existing *build error logging* by:
- Fixing regex escapes in `BuildLogSlotExtractor` (used `\\s` properly; previously caused `illegal escape character` and mis-matching).
- Adding classifiers for `Illegal escape character`, `Bad hyphen escape`, `split("\\s+")` misuse, and `Bm25LocalIndex` symbol errors.
- Introducing **NDJSON** export via `CfvmNdjsonWriter`.
- Introducing **BuildErrorReporter** and a CLI `ScanBuildLogMain` with a Gradle task `:cfvm-raw:scanBuildLog`.

## Usage
1) Produce a build log text file (e.g., `./gradlew build > BUILD_LOG.txt 2>&1`).
2) Run scanner:
   ```
   ./gradlew :cfvm-raw:scanBuildLog --args="--log BUILD_LOG.txt --out build-logs/build-errors.ndjson --summary build-logs/build-error-summary.json --session dev"
   ```
3) Outputs:
   - `build-logs/build-errors.ndjson` — one RawSlot per line
   - `build-logs/build-error-summary.json` — counts per error code

## Why now?
- Aligns with **CFVM‑Raw** telemetry and *Z‑System* resilience (error slots are now first‑class events).
- Enables *Soak/Probe* style post-build analytics and regression tracking.

