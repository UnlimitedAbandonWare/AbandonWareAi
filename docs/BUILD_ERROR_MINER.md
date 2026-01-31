
# Build Error Miner (v1.2)

**What it does**
- Scans log files from Gradle/Maven/Javac/Kotlin/Spring for failure signatures.
- Normalizes file paths/line numbers → stable *patterns*.
- Produces JSON/CSV/NDJSON + Markdown. Default output under `analysis/`.

**How to run**

```bash
# From repo root
./tools/run_build_error_miner.sh /path/to/logs_or_zip analysis/latest_build_errors
# Or directly
python tools/build_error_miner.py scan --in "/path/to/logs,/path/to/another.zip" --out analysis/latest_build_errors
```

**What to look for**

- Top codes by count (e.g., `JavacCannotFindSymbol`, `GradleBuildFailed`).
- Per-file (or per-zip) sections with 2–3 context lines per hit.
- NDJSON (`.ndjson`) for streaming or SSE telemetry ingestion.

**Notes**

- The miner is idempotent and safe to run after build.
- Large/binary files (>15MB or containing null bytes) are skipped.
- Add/extend patterns in `tools/build_error_miner.py::PATTERNS`.

**Gradle integration (optional)**

1) In `build.gradle.kts` add: `apply(from = "build-logic/buildErrorMiner.gradle.kts")`
2) Run: `./gradlew mineBuildErrors`
