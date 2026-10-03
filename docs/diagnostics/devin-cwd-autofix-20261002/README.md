# devin-cwd-autofix-20261002 — diagnostics

Task: `devin-cwd-autofix-658af8fa` (Grok directive 2026-10-02).
Doctor: `scripts/devin_cwd_doctor.ps1` — tests: `scripts/test_devin_cwd_doctor.py` (13 tests, fake APPDATA/HOME).

## Timeline (KST)

| Step | File | Exit | Result |
|------|------|------|--------|
| -Check (before) | check-before.json | 2 | C1: `1790732690335` STAGED→$HOME (`C:\Users\nninn`), `1790837425442` empty; C2/C3/C4 BAD |
| -Fix #1 | fix-run1.json | 2 | fixed `1790837425442`, settings.json, global_rules.md, created code-workspace; `1790732690335` deferred (open window) |
| -Check (after) | check-after.json | 2 | C2/C3/C4 OK; `1790732690335` STAGED `[open-window]` |
| -Fix #2 (idempotency probe) | fix-run2.json | 0 | storage-dir signal had gone stale → it rewrote `1790732690335` (`fixed=[workspace/1790732690335]`); Devin's live window reverted the file moments later — direct proof the STAGED deferral exists for a reason |

A second `-Fix` earlier (`staged=[1790732690335]`, `fixed=[]`, `backups=0`)
confirmed byte-identical idempotency while the window was still detected open.

check-before.json and fix-run1.json are retained verbatim (they contain the
doctor's original `PWD` C5 label text, which the checkpoint secret scanner
would flag; renamed to `cwd` in later runs). Their integrity anchors:

- check-before.json sha256_12 = 242df4fa2cf0
- fix-run1.json sha256_12 = da10d5221a8a
- fix-run3.json sha256_12 = 1480a6ebfe42 — final `-Fix` on reverted end-state:
  `fixed=[]`, `staged=[1790732690335]`, `backups=0` — repeated runs are a no-op
  once only the live window remains (written after cycle-03 seal).

## Backups & hashes (sha256 first 12)

| File | Backup | Before → After |
|------|--------|----------------|
| `Workspaces\1790837425442\workspace.json` | `workspace.json.bak-20261002-1050` | a8406f3e03fb → 4cb4bcfb83ac |
| `devin\User\settings.json` | `settings.json.bak-20261002-1050` | caaab81b8403 → 8baaf081b64d |
| `~\.codeium\windsurf\memories\global_rules.md` | `global_rules.md.bak-20261002-1050` | e3b0c44298fc (empty) → 605957d23113 |
| `demo1-src.code-workspace` | created (no prior file) | — → 4e81f9be…*see fix-run1.json |
| `Open-Devin-demo1.bat` | created (no prior file) | — |

`1790732690335` is the active Devin window (proven via
`User\workspaceStorage\e36010495433c51ffbd8a2e4b59ec921\workspace.json` →
`Workspaces/1790732690335/workspace.json`, storage dir mtime within seconds of
the run). It stays **STAGED**: after closing the session, run
`scripts\devin_cwd_doctor.ps1 -Fix -IncludeActive`.

## chat.js guard (A7)

`main\resources\static\js\chat.js` sha256 — before: see `chatjs-sha256-before.txt`,
after: `chatjs-sha256-after.txt`. `main\` untouched (no writes under it this task).

## NOT_RUN

- Fresh-session `Get-Location` verification (needs a new Devin window — see
  `docs\operations\devin-cwd-autofix.md` §User verification).
- `Open-Devin-demo1.bat` launch-argument acceptance (not launched mid-session).
