# demo-1 Grok Bridge Rules

Loaded by: Grok CLI (project rules, requires `Project trusted: yes`).
Other agents read `AGENTS.md` + `.agents/skills/` directly; this file is a
pointer bridge, not a rule duplicate. Verify loading with `grok inspect`
(Project Instructions >= 1).

## Project root

- Project Root is exactly `<repo>`. All relative
  paths (scripts, main\java, docs) resolve from here.

## Task entry

- Read `docs/PROJECT_STATUS.md` first, then `python -B scripts/work_journal.py list --active`
  to see in-flight journals before starting work.
- Before starting work or judging deadlines and added scope, READ the shared [guard-deadline-scope](../../docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md) body.
  Planning margin covers the whole task; preserve existing authority, verification, and the user hard cap.
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
- Sole valid main remote is `AbandonWareAi`
  (`https://github.com/UnlimitedAbandonWare/AbandonWareAi`) — the only valid
  remote is `origin` at that URL; never fetch/push to another remote, never
  add a second remote, and report a leftover `origin` pointing anywhere else
  (AGENTS.md `DEMO1-GIT-REMOTE-SOLE`).

## Secrets and spend

- Never print, log, commit, or send secret values — env var NAMES only.
- Agent spend/model order: `$demo1-agent-api-spend-guard` SSOT
  (`configs/agent-api-spend-guard.yaml`) — codex_credits → external_paid_api →
  free_tier → local_ollama; paid ON by default, `AWX_AGENT_ALLOW_PAID_MODELS=0`
  is the kill switch. Product routing keeps `configs/api-routing.yaml` order.

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
