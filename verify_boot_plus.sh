#!/usr/bin/env bash
set -euo pipefail

LOG="gptpro_boot_plus.log"
: > "$LOG"

# Run the app for a short window then stop it.
( bash ./gradlew --no-daemon -x test bootRun ) > "$LOG" 2>&1 &
PID=$!

sleep 45 || true
if ps -p "$PID" > /dev/null 2>&1; then
  kill "$PID" || true
  sleep 2 || true
  kill -9 "$PID" || true
fi

assert_grep_absent() {
  local pattern="$1"
  if grep -E -q "$pattern" "$LOG"; then
    echo "[FAIL] Found pattern: $pattern"
    echo "--- tail ---"
    tail -n 120 "$LOG" || true
    exit 1
  fi
}

assert_grep_absent_unless_tag() {
  local pattern="$1"
  local allow_tag="$2"
  if grep -E -q "$pattern" "$LOG"; then
    if grep -q "$allow_tag" "$LOG"; then
      echo "[WARN] Found '$pattern' but allow-tag '$allow_tag' present (allowed)."
    else
      echo "[FAIL] Found pattern: $pattern (no allow-tag '$allow_tag')"
      echo "--- tail ---"
      tail -n 120 "$LOG" || true
      exit 1
    fi
  fi
}

# Boot-blockers
assert_grep_absent "PlaceholderResolutionException"
assert_grep_absent "Could not resolve placeholder"
assert_grep_absent "UnsatisfiedDependencyException"
assert_grep_absent "BeanCreationException"
assert_grep_absent "ConfigurationPropertiesBindException"
assert_grep_absent "ConverterNotFoundException"
assert_grep_absent "DuplicateKeyException"
assert_grep_absent "found duplicate key"
assert_grep_absent "Application run failed"

# Netty bind is sometimes acceptable if the app logs an explicit skip tag.
assert_grep_absent_unless_tag "BindException: Address already in use" "NETTY_BIND_SKIP"
assert_grep_absent_unless_tag "java\\.net\\.BindException" "NETTY_BIND_SKIP"

echo "[OK] verify_boot_plus: no blocking patterns (or allowed by tags). See $LOG"
exit 0
