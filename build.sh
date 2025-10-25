#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="${LOG_DIR:-analysis}"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/gradle_build_$(date +%Y%m%d_%H%M%S).log"

# Run Gradle and capture logs
set +e
./gradlew :app:clean :lms-core:clean | tee "$LOG_FILE"
CLEAN_EXIT=${PIPESTATUS[0]}
if [[ $CLEAN_EXIT -ne 0 ]]; then
  EXIT_CODE=$CLEAN_EXIT
else
  ./gradlew -Dlangchain.version=${LANGCHAIN_VERSION:-1.0.1} :lms-core:build :app:bootJar | tee -a "$LOG_FILE"
  EXIT_CODE=${PIPESTATUS[0]}
fi
set -e

# Analyze build output + source (non-fatal)
if command -v python3 >/dev/null 2>&1; then
  PY=python3
else
  PY=python
fi
$PY scripts/analyze_build_output.py --log "$LOG_FILE" --code-root "$(cd .. && pwd)" || true

exit ${EXIT_CODE:-0}
