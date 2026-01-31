#!/usr/bin/env bash
set -euo pipefail

branches=(
  "pr/1-orchestrator-guards"
  "pr/2-consent-partial"
  "pr/3-tool-schemas"
  "pr/4-websearch-gateway-rrf"
  "pr/5-observability-sse-dlq"
  "pr/6-flowdsl-when-parallel-retry"
)

for b in "${branches[@]}"; do
  echo "Creating branch: $b"
  git checkout -b "$b" || true
done

echo "Done."
