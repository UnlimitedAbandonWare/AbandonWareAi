---
name: demo1-skill-uaw-evolve
description: Use when re-running the demo-1 skill-set optimization loop — harvest usage signals, score every skill, Self-Ask the merge/cold candidates, apply bounded frontmatter fixes, and report only the delta vs the previous run.
---

# demo1 Skill UAW Evolve

Evolver loop for `.agents/skills` itself (UAW mechanism cards:
`var/codex-assist-uaw-skill-opt/uaw-mechanism-cards.md`). One cycle =
harvest -> score -> critic -> bounded edit -> verify. Never deletes or moves a
skill folder; COLD/MERGE stay proposals.

## When
- A demo-1 task asks to measure, rank, or improve the skill set.
- The router index drifted from the catalog (unindexed-but-useful skills).
- Frontmatter/description quality needs a deterministic re-audit.

## Run (all stdlib-only, deterministic)

```powershell
$PY="C:\Users\nninn\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe"
& $PY -B scripts\skill_signal_harvest.py --since-days 3
& $PY -B scripts\skill_uaw_score.py
& $PY -B scripts\test_skill_uaw_score.py
```

Outputs land in `var\codex-assist-uaw-skill-opt\` (signals, verdicts.csv/md).
Compare against the previous run's `verdicts.csv` — report only the delta (M06:
newly TIGHTEN, newly FIXED, score moves), never the whole table again.

## Steps
1. Harvest signals (Codex rollouts, Devin logs, journals, handoff docs).
2. Score all skills -> verdicts.csv (KEEP / TIGHTEN / FIX-FRONTMATTER /
   MERGE-PROPOSE / COLD-PROPOSE; thresholds at top of skill_uaw_score.py).
3. Self-Ask every MERGE/COLD + top-5 over-read skills -> critic.md.
4. Lease + checkpoint, then edit at most 12 SKILL.md files — frontmatter
   description and first-15-lines only. Backup first
   (`backup\<name>\SKILL.md` + `restore_skills.ps1` dry-run).
5. Blocked files (foreign live lease) become `patches\<name>.diff` only.
6. Re-run the audit + focused tests; adopt only if no new FAIL vs baseline.

## Adoption rule
A description/frontmatter change is adopted only when: frontmatter parses,
description starts with when-to-use phrasing, focused tests show zero new
failures, and routing replay shows no regression. Otherwise restore via
`restore_skills.ps1 -Apply` and record the cause.
