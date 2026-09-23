#!/usr/bin/env bash
set -euo pipefail
IN=${1:-stuff4}
OUT=${2:-analysis/stuff4_build_error_report}
python "$(dirname "$0")/build_error_miner.py" scan --in "$IN" --out "$OUT"
