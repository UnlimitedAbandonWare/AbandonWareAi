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
4. **pre-push** — the blobs inside the commits being pushed
   (`.githooks/pre-push`): the pushed commit's blob is the evidence, not
   the working-tree file on disk.

### 허용 목록 (configs/git-guard-allow.json)

- `{path, rule, oid, reason}` 네 필드가 스캔된 blob과 전부 일치하고 reason이
  비어 있지 않을 때만 allowed — 내용이 바뀌면 oid가 달라져 자동 재차단된다
  (계약 원본 `docs/agents-rules/demo1-git-guard-fast.md`:19-23).
- 규칙 id `openai`·`google-ai`는 허용 판정에서 provider-key 계열로 본다 —
  `scripts/test_*`, `*/fixtures/*`, `src/test/**` 경로에서만 허용 가능하다.
- 허용 목록의 정확 일치 적용은 가드 완화가 아니다. 완화 = 탐지 regex·skip
  목록·차단 경로 변경, 검토 없이 허용 항목 추가, `--no-verify`·훅 끄기.

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
  silence a real hit (허용 목록의 정확 일치 적용은 완화가 아님).

## Do not

- Never print secret values, matched line content, or paste raw finding text
  into reports/commits.
- Never weaken or bypass the guard to land a commit (`--no-verify` is
  forbidden; 허용 목록의 정확 일치 적용은 완화가 아님).
- `.secrets/`, `config/secrets/`, `.env*` (non-template), `apikey*`,
  `*.pem/.key/.p12/.pfx/.jks/.keystore/.der`, DB files, Spring secret
  profiles, `data/`/`var/`/`logs/` are blocked paths — not "false
  positives" to whitelist.
- No automatic tool install or `latest` download; an absent, errored, or
  timed-out scanner reports `ERROR`/`UNKNOWN` — not PASS.

## Related

- `$demo1-conditional-local-git` — the commit gate that calls this scan.
- `$demo1-vibe-git-auto-continue` — read-only diagnosis when the gate itself is blocked.
- `.githooks/pre-commit` and `.githooks/pre-push` run this guard when
  `core.hooksPath=.githooks` (install: `scripts/install_git_publish_guard.ps1`).
