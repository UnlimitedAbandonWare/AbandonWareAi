---
name: demo1-git-secret-guard
description: Use when a demo-1 commit, publish review, or secret suspicion needs the staged-blob/tree/history scan
---

# demo1-git-secret-guard

PROTO: login/security expectations are LOW; do not enforce prod auth unless user says harden (AGENTS.md `DEMO1-PROTOTYPE-AUTH-LIGHT`, `docs/PROTOTYPE_AUTH.md`). Secret scanning itself still applies — low auth never means plaintext credentials in commits.

Secret gate for Git flows on this root. Findings print `path` + `line` +
rule id only — never the matched value. A scanner that is missing, fails,
or times out is `ERROR`/`UNKNOWN`, never a PASS.

## When

- Before a local commit, while reviewing a possible publish/push, or when a
  file may contain credential material. Match the scope to the moment —
  scanning the wrong surface is a miss, not a pass.

## Scopes

1. **review** — the changes and any report/patch being handed off; the
   working-tree files or the patch text itself.
2. **pre-commit** — the real staged blobs in the index
   (`scripts/git_staged_guard.py`); the disk file is not the evidence.
   An AM/MM file carries two versions — state which one was scanned.
3. **publish-review** — the candidate tree AND the outgoing history
   (`scripts/git_publish_review.py`): a secret deleted from the final file
   can still sit inside a commit being sent. Target URL and refs are checked
   there too; a clean tree does not clear history.

## Do

```powershell
# staged blobs (index) — what the pre-commit hook runs
python -B scripts/git_staged_guard.py --root .

# publish readiness: candidate tree + outgoing history + target (read-only)
python -B scripts/git_publish_review.py --root . --candidate HEAD --base <remote-oid> --remote origin

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
- `.env.example`-style templates pass by name **and** content checks; a file
  merely named like a placeholder still fails on a real-looking value.
- Fix findings by removing the value, moving it into an ignored env/local
  file, or unstaging the protected path — never by editing guard patterns to
  silence a real hit.

## Do not

- Never print secret values, matched line content, or paste raw finding text
  into reports/commits.
- Never weaken or bypass the guard to land a commit (`--no-verify` is
  forbidden).
- `.secrets/`, `config/secrets/`, `.env*` (non-template), `apikey*`,
  `*.pem/.key/.p12/.pfx/.jks/.keystore/.der`, DB files, Spring secret
  profiles, `data/`/`var/`/`logs/` are blocked paths — not "false
  positives" to whitelist.
- No automatic tool install or `latest` download; an absent, errored, or
  timed-out scanner reports `ERROR`/`UNKNOWN` — not PASS.

## Related

- `$demo1-conditional-local-git` — the commit gate that calls this scan.
- `$demo1-git-doctor` — read-only diagnosis when the gate itself is blocked.
- `.githooks/pre-commit` and `.githooks/pre-push` run this guard when
  `core.hooksPath=.githooks` (install: `scripts/install_git_publish_guard.ps1`).
