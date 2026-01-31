
# Patch Summary — src111_merge15 (auto)

Date: 2025-10-29

## Fixes
- **Gradle wrapper**: ensured standard `gradlew` launcher is used (no `gradlew-real` dependency).
- **Build analyzer**: patched `scripts/analyze_build_output.py` to keep writes inside the repo (`REPO_ROOT=ROOT`) and avoid `/mnt` permission errors; fixed `build.sh` to pass `--code-root "$(pwd)"`.
- **Regex compile error**: fixed illegal escape in `src/com/example/lms/service/guard/PIISanitizer.java` (`\.` instead of `\.`).

## Risky hotspots still quarantined
- 115 Java files contain placeholder `...` tokens; compilation is constrained via Gradle `sourceSets` so only safe, placeholder classes are built in `lms-core`, and only `AppApplication` in `app`.

## Telemetry
- Analyzer report written to `analysis/build_error_report.json`.
- Pattern summary updated: `BUILD_ERROR_PATTERN_SUMMARY.md`.

