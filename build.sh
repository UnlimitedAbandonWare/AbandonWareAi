#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="${LOG_DIR:-analysis}"
mkdir -p "$LOG_DIR"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_DIR/gradle_build_${TS}.log"

echo "[build.sh] preflight fixes (Gradle task)"
./gradlew -q tasks >/dev/null 2>&1 || true
./gradlew preflightFixes || true

echo "[build.sh] build :app (bootJar)"
set +e
./gradlew :app:clean :app:bootJar | tee "$LOG_FILE"
EXIT_CODE=${PIPESTATUS[0]}
set -e

echo "[build.sh] analyze build log"
PYTHON_BIN="${PYTHON_BIN:-python3}"
if command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  "$PYTHON_BIN" tools/build_error_guard.py --log "$LOG_FILE" || true
fi

exit ${EXIT_CODE:-0}
