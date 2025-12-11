#!/usr/bin/env bash
set -euo pipefail

LOG="gptpro_boot.log"
: > "$LOG"

# Optional: give the app up to 45s to either fail fast or print healthy banners.
( bash ./gradlew --no-daemon -x test bootRun ) > "$LOG" 2>&1 &
PID=$!

# Wait a bit then kill (bootRun is long‑running when healthy).
sleep 45 || true
if ps -p "$PID" > /dev/null 2>&1; then
  kill "$PID" || true
  sleep 2 || true
  kill -9 "$PID" || true
fi

# Scan for known boot‑blocking patterns
if grep -E -q "PlaceholderResolutionException|Could not resolve placeholder|Application run failed|UnsatisfiedDependencyException|BeanCreationException|ConfigurationPropertiesBindException|ConverterNotFoundException|DuplicateKeyException|found duplicate key" "$LOG"; then
  echo "[FAIL] Boot‑blocking errors detected. See $LOG"
  exit 1
fi

echo "[OK] No boot‑blocking errors found. See $LOG"
exit 0

# Auto-added default pattern file
PATTERN_FILE="${PATTERN_FILE:-quality/build_error_patterns.txt}"
