#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Offline-friendly boot verification runner
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

LOG="$PROJECT_DIR/gptpro_boot.log"
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
    echo "[verify_boot] GPTPRO_GRADLE_MODE=system but 'gradle' is not on PATH." >&2
    exit 2
  fi
  GRADLE_CMD=(gradle)
elif [[ "$MODE" == "auto" ]]; then
  if ! wrapper_dist_cached; then
    echo "[verify_boot] Gradle Wrapper distribution is not cached; offline wrapper download will fail." >&2
    echo "  - Option A: run tools/prepare_offline_gradle_cache.sh on a machine with internet, then reuse GRADLE_USER_HOME." >&2
    echo "  - Option B: provide local gradle-*-bin.zip and set GRADLE_DIST_ZIP=... (tools/gradle_wrapper_localize.sh)." >&2
    if have_cmd gradle; then
      echo "[verify_boot] Falling back to system gradle (version may differ from wrapper)." >&2
      GRADLE_CMD=(gradle)
    else
      echo "[verify_boot] No system gradle found. Cannot run boot verification in this offline environment." >&2
      exit 2
    fi
  fi
fi

# Optional: give the app up to 45s to either fail fast or print healthy banners.
( "${GRADLE_CMD[@]}" "${GRADLE_ARGS[@]}" ) > "$LOG" 2>&1 &
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

# Netty bind errors: we keep full stack traces (for debugging), but allow
# fail-soft skips when explicitly tagged.
if grep -E -q "BindException: Address already in use|java\.net\.BindException" "$LOG"; then
  if grep -q "NETTY_BIND_SKIP" "$LOG"; then
    echo "[WARN] BindException detected but NETTY_BIND_SKIP tag present (allowed). See $LOG"
  else
    echo "[FAIL] BindException detected without NETTY_BIND_SKIP tag. See $LOG"
    exit 1
  fi
fi

echo "[OK] No boot‑blocking errors found. See $LOG"
exit 0
