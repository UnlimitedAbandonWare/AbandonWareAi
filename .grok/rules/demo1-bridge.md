# demo-1 Grok Bridge Rules

Loaded by: Grok CLI (project rules, requires `Project trusted: yes`).
Other agents read `AGENTS.md` + `.agents/skills/` directly; this file is a
pointer bridge, not a rule duplicate. Verify loading with `grok inspect`
(Project Instructions >= 1).

## Project root

- Project Root is exactly `C:\AbandonWare\demo-1\demo-1\src`. All relative
  paths (scripts, main\java, docs) resolve from here.

## Task entry

- Read `docs/PROJECT_STATUS.md` first, then `python -B scripts/work_journal.py list --active`
  to see in-flight journals before starting work.
- `python -B scripts/agent_preflight.py --root .` gives the canonical
  root/lease/journal report.

## File changes (work ledger)

- Journal: `python -B scripts/work_journal.py open|note|close`.
- Before each change-set: `python -B scripts/codex_work_checkpoint.py begin`
  (failed begin = change does not start), then seal + verify + finish.
- Source edits require a target-scoped lease via
  `python -B scripts/agent_scope_lease.py claim`; do not touch files under a
  foreign active lease.
- Git is read-only evidence at most — never a restore source.

## Secrets and spend

- Never print, log, commit, or send secret values — env var NAMES only.
- Prefer free/local models and tools before paid providers; no paid fanout
  unless `AWX_AGENT_ALLOW_PAID_MODELS=1`.

## Skills

- Skills live in `.agents/skills/`; pick ONE from `.agents/skills/INDEX.md`
  (or `SKILL.md` names) and read the file directly. Manual discovery, not
  auto-load.
- `AGENTS.md` is the always-on contract SSOT; `.windsurf/rules/` mirrors it
  for Windsurf — do not copy rule text into new adapters.

## Reporting style

- Distinguish verified-in-this-checkout facts from inference; mark
  `evidence_needed` instead of guessing.
- Report one-line status first, then details; include real exit codes for
  any verification claim.
