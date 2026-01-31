#!/usr/bin/env bash
set -euo pipefail

# This guard is intended for the demo-1 Gradle project under project/src/demo-1.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"      # demo-1 module root
TOP_DIR="$(cd "${ROOT_DIR}/.." && pwd)"        # project/src aggregate root

PAT_FILE="${ROOT_DIR}/tools/build_error_patterns.txt"
# Resolve pattern file (module-local first, then repo-root fallback)
if [[ ! -f "$PAT_FILE" ]]; then
  CANDIDATES=(
    "${ROOT_DIR}/../build_error_patterns.txt"
    "${ROOT_DIR}/../../build_error_patterns.txt"
    "${ROOT_DIR}/../../../build_error_patterns.txt"
  )
  for c in "${CANDIDATES[@]}"; do
    if [[ -f "$c" ]]; then
      PAT_FILE="$c"
      break
    fi
  done
fi
LOG_CANDIDATES=(
  "${ROOT_DIR}/build/logs/build.log"
  "${ROOT_DIR}/build/reports/tests/test/index.html"
  "${ROOT_DIR}/build/tmp/compileJava/previous-compilation-data.bin"
)
EXTRA_PATTERNS=("illegal escape character" "illegal text block open delimiter sequence, missing line terminator")

echo "[guard] scanning for known bad patterns..."
rc=0

# 1) Scan known build/compile error patterns in recent logs
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

# 2) Banned placeholder tokens directly in sources (e.g. {스터프3})
if grep -RIn --color=never "\{스터프3\}" "${ROOT_DIR}/src" >/dev/null 2>&1; then
  echo "::warning file=src:: Found placeholder token {스터프3} in sources (non-fatal; please clean up if possible)"
fi

# 3) check duplicate top-level YAML keys (retrieval) in application.yml files
check_duplicate_yaml_keys() {
  local ROOT="$1"
  local files=(
    "${ROOT}/src/main/resources/application.yml"        # aggregate root app (if present)
    "${ROOT}/app/src/main/resources/application.yml"    # legacy app module
    "${ROOT}/app/resources/application.yml"             # legacy resources layout
    "${ROOT}/demo-1/src/main/resources/application.yml" # demo-1 module
    "${ROOT}/demo-1/src/main/resources/application-local.yml" # demo-1 local profile (bootRun)
  )
  for f in "${files[@]}"; do
    if [[ -f "$f" ]]; then
      # duplicate 'retrieval:' at root level
      local cnt_retrieval
      cnt_retrieval=$(grep -E "^[[:space:]]*retrieval:" -n "$f" | wc -l | tr -d ' ')
      if [[ "$cnt_retrieval" -gt 1 ]]; then
        echo "[guard] duplicate 'retrieval:' keys detected in $(realpath "$f") (count=$cnt_retrieval)"
        echo "[guard] please merge into a single 'retrieval:' mapping. Failing early."
        rc=1
      fi

      # duplicate 'onnx:' at root level → this triggers SnakeYAML DuplicateKeyException
      local cnt_onnx
      cnt_onnx=$(grep -E "^[[:space:]]*onnx:" -n "$f" | wc -l | tr -d ' ')
      if [[ "$cnt_onnx" -gt 1 ]]; then
        echo "[guard] duplicate 'onnx:' keys detected in $(realpath "$f") (count=$cnt_onnx)"
        echo "[guard] this will cause org.yaml.snakeyaml.constructor.DuplicateKeyException at Spring Boot startup."
        echo "[guard] please merge into a single 'onnx:' mapping (or move nested configs under 'abandonware.reranker.onnx' / 'zsys.onnx')."
        rc=1
      fi
    fi
  done
}


# run duplicate-key check using the aggregate project/src root
check_duplicate_yaml_keys "$TOP_DIR"

exit $rc
