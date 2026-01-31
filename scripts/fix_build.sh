#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[fix_build] Applying build matrix fixes..."
python3 scripts/apply_matrix_fixes.py || python scripts/apply_matrix_fixes.py

echo "[fix_build] Verifying Gradle wrapper..."
if [[ ! -f "gradle/wrapper/gradle-wrapper.jar" ]]; then
  echo "[fix_build] ERROR: gradle/wrapper/gradle-wrapper.jar not found" >&2
  exit 1
fi

echo "[fix_build] OK"
