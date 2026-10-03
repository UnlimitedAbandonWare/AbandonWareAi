---
name: demo1-vibe-git-auto-continue
description: Use when a demo-1 vibe Git/lease soft branch needs auto-continue
---

# demo1-vibe-git-auto-continue

Vibe-session Git/lease auto-continue: soft branches that are already agreed
proceed without a user question card — each one leaves a single
`AUTO:<reason>` journal line instead. Hard constraints stay hard; this skill
grants no new authority over them.

## When

- During demo-1 vibe work a Git/lease soft decision appears: a stale
  `index.lock`, committing only this session's paths while foreign staging
  (e.g. SelfAsk) stays staged, an additive fix to the Git gate tools, a
  missing `git` on PATH, or a foreign lease on a needed target.

## Do — soft auto-continue (act, journal `AUTO:<reason>`, no card)

- **Stale `index.lock`**: handled inside
  `python -B scripts/agent_git_vibe_commit.py` (or directly via
  `python -B scripts/conditional_local_git.py lock --repo . --backup-dir data/agent-handoff/<taskId>`).
  The lock is moved to `index.lock.bak-<yyyymmdd>` only when it is 0 bytes
  and past the age threshold (`lock --days` and `--stale-lock-days` share
  one default `DEFAULT_STALE_LOCK_DAYS` = 0.25 = 6h), or when it carries
  the gate's own `conditional-local-git:` marker — a selected-commit whose
  writer died mid-flight. Both shapes still require no confirmed `git.exe`
  writer and an unchanged index hash, and the marker path adds a short
  settle window first. Any unmet condition → `action: preserved` + reason →
  `deferred=index-lock` — report BLOCKED, never force-delete.
- **Foreign staging stays staged**: commit only this session's paths with
  `python -B scripts/agent_git_vibe_commit.py --repo . --path <p> [--path ...] --message-file <file>`
  — preserve-foreign-staged is the orchestrator's default (low-level:
  `commit --preserve-foreign-staged`; `--strict-staging` names the
  exact-match contract). Foreign staged entries must stay byte-identical —
  confirm `foreignStagingPreserved` in the result and never unstage them.
- **Unowned staging/files**: keep them out of the commit, journal
  `AUTO:excluded-foreign=<paths>`. No "whose file is this?" question.
- **Gate-tool changes**: additive patches that keep or strengthen the hard
  constraints (selective commit, foreign-staging preservation, stronger
  scans) proceed without asking "may I add this?". Rewriting, deleting, or
  weakening the policy/tool does not.
- **`git` missing on PATH**: `conditional_local_git.py` resolves `git.exe`
  from known install paths (`F:\git\cmd\git.exe`, Program Files). Do not
  stop or ask on "no git"; only a real absence reports `git-not-found` →
  BLOCKED.
- **Existing conditional-Git policy** (AGENTS `DEMO1-GIT-LOCAL-FIRST`,
  `.grok`/`.windsurf` rules): reuse, never overwrite; sync wording in place
  keeping BEGIN/END markers. A foreign lease on a target → classify first
  (`lease_conflict_autoflow.py scan`): **stale** (TTL/heartbeat expired,
  owner not proven alive) is auto-reclaimed via `reclaim` /
  `plan --execute` — journal `AUTO:lease-reclaimed=<owner|reason>`, no
  card; **live** gets one `request-release`, then work the unblocked files
  and defer the rest — never force-unlock a live lease, never ask the
  user to relay.

## Do not

- Never auto-approve the hard list: secrets/`apikey.txt`/openssl material in
  logs or commits; `.git` deletion, history rewrite, `reset --hard`,
  `clean -fdx`, restoring the worktree from an old HEAD; push/pull/fetch/
  merge/rebase/`git tag`/version bump or release files (VERSION/CHANGELOG/
  RELEASE root files, semver, `gh release`, artifact uploads) without an
  explicit user request; touching another session's lease, journal, or staged
  paths; `add -A`/`add .`/`commit -a`/`--no-verify`; bulk deletes outside
  GraphRAG/Focus/Display scope; sandbox bypass. These stay BLOCKED until the
  user says otherwise.
- Do not surface a 1/2 choice card for any soft branch above — act under the
  stated conditions, journal `AUTO:<reason>`, report.
- Do not weaken `conditional_local_git.py` verdicts, skip the staged scan,
  or carry secret values into output.

## Related

- `$demo1-conditional-local-git` — command gate + policy
  (`check|scan|commit|lock`); the plain commit path keeps exact-match
  staging semantics, `scripts/agent_git_vibe_commit.py` is the one-call
  agent entry that wraps it.
- `$demo1-git-secret-guard` — staged/working secret scan internals.
- `$demo1-git-vibe-workflow` — end-to-end commit orchestration this skill
  plugs into.
- `$demo1-git-doctor` — read-only block/ownership diagnosis; when the
  answer is unclear, diagnose first, then apply the soft branches here.
- `$agent-scope-lease` — lease check/claim/release, stale `reclaim`, and
  the once-per-fingerprint `request-release` flow.
