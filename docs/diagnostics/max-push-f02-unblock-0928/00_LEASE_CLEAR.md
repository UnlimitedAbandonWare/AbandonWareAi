# F02 scanner lease

Contract: `DEMO1-GROK-MAXPUSH-F02-UNBLOCK-20260928`
Checked: `2026-09-28T14:11:10Z`

## Jackson lock

`checkpoint-scanner-jackson-read-0928` scope claims are empty. The source-edit lock `5faf346470c242fead5d640f74b07fa1` is absent. While it was live, reclaim dry-run returned `live-lease`. It was gone before Grok edited the scanner.

## Devin claim

`devin-f02-scanner-unblock-0928-33a0e48d` claimed the scanner at `2026-09-28T13:56:17Z` and released it `done` at `2026-09-28T14:10:46Z`. Journal result `verified`. Lease status after release: `absent`.

Scan at `2026-09-28T14:11:10Z` for `scripts/codex_work_checkpoint.py`: `blockedTargets` empty.

Grok did not edit the scanner or its regression file.
