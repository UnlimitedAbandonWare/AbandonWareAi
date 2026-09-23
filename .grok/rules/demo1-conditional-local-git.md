# Conditional local Git (this canonical root only)

User authorization on 2026-09-23 replaces the blanket Git mutation ban for
`C:\AbandonWare\demo-1\demo-1\src` only. This file is the policy body.
`scripts/conditional_local_git.py` enforces the same rules. Do not copy this
body into other instruction files.

Codex loads `AGENTS.md`, not this Grok rule. While
`DEMO1-GIT-LOCAL-FIRST` in `AGENTS.md` and the Git sentence in
`.windsurf/rules/demo1-hard-constraints.md` still say Git is read-only, those
copies are leased by other sessions and are not edited here. For this root,
this file is the user-authorized replacement of that mutation ban. A Codex
session that has not been restarted after `AGENTS.md` itself changes stays
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

`python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <owned-path>`

This rule does not authorize application-source edits for GraphRAG, Nova Focus,
or Meta Display.
