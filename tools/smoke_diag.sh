#!/usr/bin/env bash
set -euo pipefail
host="${1:-http://localhost:8080}"
curl -sS -X POST "$host/diag/nn/gradients"   -H "Content-Type: application/json"   -d '{"model":"smoke","layers":[{"layer":"L1","norm":1e-4},{"layer":"L2","norm":5e-4},{"layer":"L3","norm":2e-3}]}' | jq . || true
