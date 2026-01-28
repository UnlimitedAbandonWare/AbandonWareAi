#!/usr/bin/env bash
set -euo pipefail
# Copies configs (presets, prometheus, grafana) into repo
rsync -av ../configs/ ../../
echo "Configs copied. Commit at your discretion."
