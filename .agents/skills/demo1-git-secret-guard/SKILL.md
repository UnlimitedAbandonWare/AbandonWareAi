---
name: demo1-git-secret-guard
description: Use when a demo-1 commit, push review, or secret suspicion needs the staged/working scan
---

# demo1-git-secret-guard

Secret gate for Git flows on this root. Findings print `path` + `line` +
rule id only — never the matched value.

## When

- Before a local commit, while reviewing a possible publish/push, or whenever
  a file may contain credential material.

## Do

```powershell
# staged blobs (index) — what the pre-commit hook runs
python -B scripts/git_staged_guard.py --root .

# manual audit of staged paths (falls back to tracked+untracked when index is empty)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\git_secret_guard.ps1 -Mode manual
# whole-tree audit
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\git_secret_guard.ps1 -Mode manual -ScanAll
# self-test (no repo state needed)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\git_secret_guard.ps1 -SelfTest
```

- `-Mode pre-commit` delegates to `git_staged_guard.py` and refuses
  `-ScanAll`/`-Path` overrides — the index is the evidence, not the tree.
- Exit 0 = clean; exit 1 = findings (`[BLOCK] path=... line=... rule=...`)
  or a guard failure. `git_staged_guard.py` emits JSON reason codes.
- Fix findings by removing the value, moving it into an ignored env/local
  file, or unstaging the protected path — never by editing guard patterns to
  silence a real hit.

## Do not

- Never print secret values, matched line content, or paste raw finding text
  into reports/commits.
- Never weaken or bypass the guard to land a commit (`--no-verify` is
  forbidden).
- `.secrets/`, `config/secrets/`, `.env*` (non-template), `apikey*`,
  `*.pem/.key/.p12/.pfx/.jks/.keystore/.der`, DB files, and Spring secret
  profiles are blocked paths — not "false positives" to whitelist.

## Related

- `$demo1-conditional-local-git` — the commit gate that calls this scan.
- `.githooks/pre-commit` and `.githooks/pre-push` run this guard when
  `core.hooksPath=.githooks` (install: `scripts/install_git_publish_guard.ps1`).
