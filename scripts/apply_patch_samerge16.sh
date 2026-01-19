#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-.}"
PATCH_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "[+] Copying plans ..."
mkdir -p "$ROOT/plans"
cp -f "$PATCH_DIR/plans/hypernova.v1.yaml" "$ROOT/plans/"

echo "[+] Copying addons sources ..."
mkdir -p "$ROOT/addons/formulas_pack/addons/formulas/java"
cp -rf "$PATCH_DIR/addons/formulas_pack/addons/formulas/java/" "$ROOT/addons/formulas_pack/addons/formulas/"

echo "[+] Copying ops policies ..."
mkdir -p "$ROOT/ops/hypernova/policies"
cp -f "$PATCH_DIR/ops/hypernova/policies/hypernova.policies.yaml" "$ROOT/ops/hypernova/policies/"

echo "[+] Copying config overlay ..."
mkdir -p "$ROOT/config"
cp -f "$PATCH_DIR/config/application-sa16-patch.yaml" "$ROOT/config/"

echo "[+] Copying docs ..."
mkdir -p "$ROOT/docs"
cp -f "$PATCH_DIR/docs/INTEGRATION_GUIDE_SAmerge16_patch.md" "$ROOT/docs/"

echo "[✓] Patch applied. Review docs/INTEGRATION_GUIDE_SAmerge16_patch.md"