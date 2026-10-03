# demo1-copy-residue-cleanup

Use when the demo-1 root accumulates copy residue — duplicated directive/report
copies, model or cache copies inside `data/agent-handoff`, stale `__patch_drop__`
patch/log residue, untracked `agent-prompts` brief copies, extra `.gradle-*`
homes, or unreferenced empty dirs — and the goal is to delete only what is
provably redundant, quarantine what is ambiguous, and index the rest.

## Why

Hand-deleting inside `data/agent-handoff`, `__patch_drop__`, or `agent-prompts`
risks wiping checkpoint preimages, live lease state, referenced briefs, or
another agent's in-flight work. This tool classifies first, executes only a
hash-pinned plan, and keeps every removal recoverable (sha-matched original or
quarantine manifest + restore.ps1).

## Classification contract (four classes + two report-only)

- `DELETE` — untracked AND one of: byte-identical original exists elsewhere
  (sha256 match), regenerable cache (`__pycache__`, `.pytest_cache`,
  `gradle-cache`, `gradle-project`, `executionHistory`, `fileHashes`, extra
  root `.gradle-*` homes), or unreferenced empty dir.
- `QUARANTINE` — untracked, no sha-matched original, ambiguous use. Moved to
  `_quarantine/<date>/src/<relpath>` with `quarantine-manifest.json` +
  `restore.ps1`. Retention: 14 days. Examples: >7d completed task `before/*.bin`,
  model copies with no in-use original, untracked prompts absent from Downloads,
  old `*.pending.md`/logs/cycle JSON in `__patch_drop__`.
- `KEEP` — stay in place, indexed in `var/copy-residue-cleanup/KEEP_INDEX.md`:
  REPORT/journal/checkpoint/manifest/decision/INDEX files, paths referenced by
  AGENTS/skills/docs/rules/PROJECT_STATUS, quarantine-family failure cards,
  `pki-validation`, live `__patch_drop__` machinery.
- `TRACKED_CANDIDATE` — git-tracked; listed in `TRACKED_CANDIDATES.md` for a
  user decision, never acted on.
- `SKIP` — active-lease/journal prefixes, <72h-modified files or dirs,
  in-progress journal dirs, forbidden zones, safe-cleanup-covered areas.

## Commands

```powershell
# 1. read-only plan -> plan.json + PLAN.md + printed planSha256
python -B scripts/copy_residue_cleanup.py plan --root . --ledger <ledger-dir> `
  --git-exe "F:\git\cmd\git.exe" `
  --original-dir "%USERPROFILE%\Downloads" --downloads-dir "%USERPROFILE%\Downloads" `
  --original-dir "%USERPROFILE%\.cache\huggingface"

# 2. apply only the hash-pinned plan (DELETE first, then QUARANTINE)
python -B scripts/copy_residue_cleanup.py apply --root . --plan <plan.json> `
  --plan-sha <printed-sha> --ledger <ledger-dir> `
  --quarantine-root "C:\AbandonWare\demo-1\demo-1\_quarantine\<tag>" --only DELETE
python -B scripts/copy_residue_cleanup.py apply ... --only QUARANTINE

# 3. restore everything in a manifest (idempotent, sha-verified)
python -B scripts/copy_residue_cleanup.py restore --manifest <quarantine-manifest.json>
# or: powershell -File <quarantine>/restore.ps1

# 4. summary
python -B scripts/copy_residue_cleanup.py report --plan <plan.json> --apply-log <apply-log.jsonl>
```

`--plan-sha` is mandatory for apply: the tool rehashes the plan file and refuses
on mismatch. Each row's sha256/size is re-verified before delete/move; drifted
or locked files are recorded `skipped-*`/`failed`, never force-touched.

## Never touch

git-tracked files (report only), `main/`, `app/`, `configs/`, `frontend/`,
`src/`, `tools/`, `bin/`, `build/`, `.gradle/`, `.git/`, `.secrets/`, `.env*`,
`var/meta-display-db/`, `data/` outside `agent-handoff`,
`__patch_drop__/source-edit-*` (locks, quarantine, heartbeats, events, scopes),
active leases, <72h-modified dirs, referenced paths, `chat.js`, running-process
`.gradle-*` homes, secret values. Existing tools stay authoritative:
`Safe-Cleanup.bat` owns `build/`, `logs/`, `__reports__/`, `__pycache__`, empty
leftovers; `Ops-Surface-Thin.bat` owns ops-surface archiving — this tool skips
those areas instead of duplicating them.

## Rules

- WhatIf/plan first; apply only after reviewing PLAN.md.
- Quarantine retention 14 days; never empty the quarantine folder.
- No lease/lock deletion, no `git rm`, no server/restart side effects.
- Unit tests: `python -B scripts/test_copy_residue_cleanup.py`.

## Related

- Engine: `scripts/copy_residue_cleanup.py` · tests: `scripts/test_copy_residue_cleanup.py`
- `Safe-Cleanup.bat` (`$demo1-safe-cleanup`), `Ops-Surface-Thin.bat` (`$demo1-ops-surface-thin`)
- Ledger: `$demo1-work-ledger` · leases: `$agent-scope-lease`
