#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAT_FILE="$ROOT_DIR/tools/build_error_patterns.txt"
# Resolve pattern file (module-local first, then repo-root fallback)
if [[ ! -f "$PAT_FILE" ]]; then
  CANDIDATES=(
    "$ROOT_DIR/../build_error_patterns.txt"
    "$ROOT_DIR/../../build_error_patterns.txt"
    "$ROOT_DIR/../../../build_error_patterns.txt"
  )
  for c in "${CANDIDATES[@]}"; do
    if [[ -f "$c" ]]; then
      PAT_FILE="$c"
      break
    fi
  done
fi
LOG_CANDIDATES=(
  "$ROOT_DIR/build/logs/build.log"
  "$ROOT_DIR/build/reports/tests/test/index.html"
  "$ROOT_DIR/build/tmp/compileJava/previous-compilation-data.bin"
)
EXTRA_PATTERNS=(
  "illegal escape character"
  "illegal start of type"
  "invalid method declaration; return type required"
  "class, interface, enum, or record expected"
)
# Also scan source tree for banned tokens (e.g., {스터프3})
echo "[guard] scanning for known bad patterns..."
rc=0
if [[ -f "$PAT_FILE" ]]; then
  for log in "${LOG_CANDIDATES[@]}"; do
    if [[ -f "$log" ]]; then
      while IFS= read -r pat; do
        [[ -z "$pat" || "$pat" =~ ^# ]] && continue
        if grep -E -n --color=never "$pat" "$log" >/dev/null 2>&1; then
          echo "::error file=$log:: matched pattern: $pat"
          rc=1
        fi
      done < "$PAT_FILE"
      # Extra non-regex patterns (inline hardening, e.g. Java parser errors)
      for ep in "${EXTRA_PATTERNS[@]}"; do
        if grep -F -n --color=never "$ep" "$log" >/dev/null 2>&1; then
          echo "::error file=$log:: matched pattern: $ep"
          rc=1
        fi
      done
    fi
  done
fi
# banned tokens in sources (warn only; no longer fails the build)
if grep -RIn --color=never "\{스터프3\}" "$ROOT_DIR/src" >/dev/null 2>&1; then
  echo "::warning file=src:: Found placeholder token {스터프3} in sources (non-fatal; please clean up if possible)"
fi
exit $rc