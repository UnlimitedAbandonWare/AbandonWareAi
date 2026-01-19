#!/usr/bin/env bash
set -euo pipefail

# Use from repo root: bash tools/apply_epoch_fix.sh
# 1) Fix accidental tokens like toEpochMilli()isis()
# 2) Optionally normalize weird System.currentTimeMillis variants

shopt -s globstar
count=0
for f in **/*.java; do
  if [[ -f "$f" ]]; then
    if grep -q "toEpochMilli()isis()" "$f"; then
      sed -i.bak 's/toEpochMilli()isis()/toEpochMilli()/g' "$f"
      ((count+=1))
    fi
    # Normalize "System.currentTimeMill..." typos to System.currentTimeMillis()
    if grep -q "System.currentTimeMill" "$f"; then
      sed -i.bak 's/System\.currentTimeMill[a-zA-Z_]*/System.currentTimeMillis/g' "$f"
      sed -i.bak 's/System\.currentTimeMillis\s*\(\)/System.currentTimeMillis()/g' "$f"
      ((count+=1))
    fi
  fi
done

echo "[apply_epoch_fix] Patched files: $count"
echo "[apply_epoch_fix] Backups (*.bak) were created next to the edited files."
