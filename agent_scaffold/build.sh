#!/usr/bin/env bash
set -euo pipefail
MANIFEST="${1:-prompts.manifest.yaml}"
OUTDIR="$(dirname "$MANIFEST")/out"
mkdir -p "$OUTDIR"
# naive concatenation builder
SYS=$(grep -A9999 '^agents:' -n "$MANIFEST" >/dev/null 2>&1; echo "")
# For demo only; real builder expected in agent_scaffold_v2.zip
echo "[demo] built prompts to $OUTDIR (noop)"
