#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Offline-friendly boot verification runner (strict)
#
# In offline sandboxes, Gradle Wrapper may fail before bootRun because it tries
# to download the Gradle distribution when the wrapper cache is missing.
#
# We fail-fast with a clear guide, and fall back to system gradle when available.
#
# Controls:
# - GPTPRO_GRADLE_MODE=auto|wrapper|system (default: auto)
# - OFFLINE_BUILD=1 -> pass --offline (default: off)
# ---------------------------------------------------------------------------

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$PROJECT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

LOG="$PROJECT_DIR/gptpro_boot_plus.log"
: > "$LOG"

MODE="${GPTPRO_GRADLE_MODE:-auto}"

have_cmd() { command -v "$1" >/dev/null 2>&1; }

wrapper_dist_cached() {
  local wrapper_props="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.properties"
  if [[ ! -f "$wrapper_props" ]]; then
    return 0
  fi
  local dist_url
  dist_url="$(grep -E '^distributionUrl=' "$wrapper_props" | head -n1 | cut -d= -f2- | tr -d '\r' || true)"
  if [[ -z "$dist_url" ]]; then
    return 0
  fi
  if [[ "$dist_url" == file:* ]]; then
    return 0
  fi
  local dist_file dist_base
  dist_file="$(basename "$dist_url")"
  dist_base="${dist_file%.zip}"
  local gh
  gh="${GRADLE_USER_HOME:-$HOME/.gradle}"
  [[ -d "$gh/wrapper/dists/$dist_base" ]]
}

GRADLE_ARGS=(--no-daemon -x test bootRun)
if [[ "${OFFLINE_BUILD:-}" == "1" ]]; then
  GRADLE_ARGS=(--offline "${GRADLE_ARGS[@]}")
fi

GRADLE_CMD=("$REPO_ROOT/gradlew")

if [[ "$MODE" == "system" ]]; then
  if ! have_cmd gradle; then
    echo "[verify_boot_plus] GPTPRO_GRADLE_MODE=system but 'gradle' is not on PATH." >&2
    exit 2
  fi
  GRADLE_CMD=(gradle)
elif [[ "$MODE" == "auto" ]]; then
  if ! wrapper_dist_cached; then
    echo "[verify_boot_plus] Gradle Wrapper distribution is not cached; offline wrapper download will fail." >&2
    echo "  - Option A: run tools/prepare_offline_gradle_cache.sh on a machine with internet, then reuse GRADLE_USER_HOME." >&2
    echo "  - Option B: provide local gradle-*-bin.zip and set GRADLE_DIST_ZIP=... (tools/gradle_wrapper_localize.sh)." >&2
    if have_cmd gradle; then
      echo "[verify_boot_plus] Falling back to system gradle (version may differ from wrapper)." >&2
      GRADLE_CMD=(gradle)
    else
      echo "[verify_boot_plus] No system gradle found. Cannot run boot verification in this offline environment." >&2
      exit 2
    fi
  fi
fi

# Run the app for a short window then stop it.
( "${GRADLE_CMD[@]}" "${GRADLE_ARGS[@]}" ) > "$LOG" 2>&1 &
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
