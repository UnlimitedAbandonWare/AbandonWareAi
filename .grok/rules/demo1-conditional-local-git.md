# Conditional local Git (this canonical root only)

User authorization on 2026-09-23 replaces the blanket Git mutation ban for
`C:\AbandonWare\demo-1\demo-1\src` only. This file is the policy body.
`scripts/conditional_local_git.py` enforces the same rules. Do not copy this
body into other instruction files.

Codex loads `AGENTS.md`, not this Grok rule. As of 2026-09-23 the copies are
aligned: `AGENTS.md` `DEMO1-GIT-LOCAL-FIRST`,
`.windsurf/rules/demo1-hard-constraints.md` (Git sentence), and
`.windsurf/rules/demo1-conditional-local-git.md` (pointer) all carry the same
conditional scope below. This file remains the policy body — do not copy it
into other instruction files; fix drift at the source instead. A session that
has not been restarted after `AGENTS.md` itself changes stays
`PATCHED_NOT_RELOADED`. Creating `AGENTS.override.md` is not a substitute:
in this directory an override replaces the whole `AGENTS.md`.

Allowed here without asking again for the same scope: `git status`, `git diff`,
selective `git add` of paths this session owns, a staged-blob secret scan, and
one local commit after that scan passes.

Still forbidden: push, remote changes, pull, fetch, merge, rebase, reset,
clean, history rewrite, deleting `.git` or `index.lock`, killing `git.exe`,
`--no-verify`, `add -A`, `add .`, `commit -a`, taking or unstaging another
session's staged paths, and committing secrets, raw conversation, databases,
models, indexes, or large logs.

The working tree and passing tests remain the restore baseline. An old HEAD is
not a restore source. A clean tree does not prove the task is done. `git status`
does not classify a file as safe to delete. A live `git.exe` writer remains a
scoped hold, not a repository-wide stop.

Local commit entry point:

Opt-in `commit --preserve-foreign-staged --path <owned-path>` adds/scans only declared paths in an isolated candidate, keeps hooks enabled, and verifies every other index entry and its staged state are unchanged; the default exact-staged-set contract remains unchanged.

`python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <owned-path>`

Commit candidate blast-radius caps are enforced by the tool (no override flag):
a candidate set over 40 paths or over 15 deletions fails with
`blast-radius-paths` / `blast-radius-deletions` (exit 2). The candidate set is
the staged set for the default commit, or the `--path` set under
`--preserve-foreign-staged`. Whole-worktree dirty counts appear in JSON
(`worktreeCounts`) for information only — they never hard-stop a scan or
commit.

Sole main remote is `AbandonWareAi`
(`https://github.com/UnlimitedAbandonWare/AbandonWareAi`) ONLY. `AbandonWare3`
is fully discarded — never treat it as a valid remote, temporary origin,
fallback, migration keep, or backup upstream. Never add a second remote for
convenience — prefer a single-repo branch/tag. If local git still lists
`AbandonWare3`, report it and never fetch/push to it or prefer its SHA over
C-root/`AbandonWareAi`; remote removal itself needs an explicit user ask. Old
docs/ZIP naming `AbandonWare3` are historical only. `scan`/`commit` JSON
reports `intendedRemote`, `forbiddenRemote`, and `originMismatch`; the gate
fails closed — `forbidden-remote` when any remote URL (any name, fetch or
push) names `AbandonWare3`, `origin-mismatch` when `origin` differs from the
sole remote — exit 2 and the commit is blocked. This tool never mutates
remotes.

This rule does not authorize application-source edits for GraphRAG, Nova Focus,
or Meta Display.
