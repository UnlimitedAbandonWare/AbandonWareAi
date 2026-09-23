#!/bin/sh
set -eu
HookDir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec python3 -B "$HookDir/source_edit_triage.py"
