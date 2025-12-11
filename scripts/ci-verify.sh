#!/usr/bin/env bash
set -euo pipefail
echo "[CI-VERIFY] Using Java toolchain if configured."
./gradlew --version || true
echo "[CI-VERIFY] Listing modules:"
./gradlew projects
echo "[CI-VERIFY] Building app with lms-core (skip tests):"
./gradlew :app:assemble -x test
echo "[CI-VERIFY] DONE"
