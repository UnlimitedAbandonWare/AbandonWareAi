#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 1) Fix project build configuration (idempotent)
scripts/fix_build.sh

# 2) Use wrapper jar directly to avoid placeholder gradlew issue
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"

LOG_DIR="${LOG_DIR:-analysis}"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/gradle_build_$(date +%Y%m%d_%H%M%S).log"

echo "[build] Running Gradle via wrapper jar..."
set +e
"$JAVA_BIN" -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain build -x test | tee "$LOG_FILE"
GRADLE_EXIT=${PIPESTATUS[0]}
set -e

# 3) Analyze build output + source (non-fatal)
if command -v python3 >/dev/null 2>&1; then
  PY=python3
else
  PY=python
fi
$PY scripts/analyze_build_output.py --log "$LOG_FILE" --code-root "$(cd .. && pwd)" || true

exit $GRADLE_EXIT
