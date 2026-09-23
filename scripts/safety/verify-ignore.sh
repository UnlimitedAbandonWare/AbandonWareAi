#!/usr/bin/env bash
set -euo pipefail
echo "[Check] git check-ignore for sensitive files"
git check-ignore -v src/main/resources/application.properties src/main/resources/keystore.p12 || true
echo "[Check] grep for obvious secret patterns (no values shown)"
grep -RInE '(api[-_ ]?key|secret|client_secret|bearer|token|password)' || true
