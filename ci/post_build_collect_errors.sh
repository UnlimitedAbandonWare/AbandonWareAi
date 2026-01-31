#!/usr/bin/env bash
set -euo pipefail
echo "[ci] collecting build error patterns..."
python3 tools/build_error_pattern_scanner.py || true
echo "[ci] done."
