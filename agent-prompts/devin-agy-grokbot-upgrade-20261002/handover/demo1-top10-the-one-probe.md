---
name: demo1-top10-the-one-probe
description: Use this when the user asks to find the biggest problems or risks in demo-1 (or "더 문제 있는 것도 알려줘"), and wants them ranked and turned into one actionable brief.
---

# demo-1 Top10 → 3 → THE ONE Probe

## Steps
1. **Probe the live tree read-only** (Project Root `C:\AbandonWare\demo-1\demo-1\src`). Priority: real execution > focused test > code > DB/trace > Git > docs/guesses.
   - `git remote -v`, `git status --porcelain`, `.git/index.lock`, branch tracking. Use `F:\git\cmd\git.exe` when possible.
   - `python -B scripts/agent_preflight.py`, lease scans (`lease_conflict_autoflow.py scan`), zombie journals (`work_journal.py`).
   - Config defaults (`matchIfMissing`, `permitAll`, feature flags in `application-meta-display.yml`, `api-routing.yaml`, `agent-api-spend-guard.yaml`).
   - AGENTS.md markers against the scripts and skills that actually exist (missing SKILL.md, intent index gaps, 404 script paths).
   - Runtime evidence when available: `Status-RAG.bat`, `Verify-RAG.bat -Json`, `Read-RAG-Debug.bat`, debug-events SAFE GET endpoints.
   - Never print secrets. Reading `.env` values is off limits. Names and presence only.
2. **Top10**: P0/P1/P2, one line each, with the measured evidence (`file:Lnn`, command output).
3. **Shortlist 3**, and say what you excluded because it is already running in another session.
4. **THE ONE** = highest (irreversibility × freshly measured × small patch). One sentence on why.
5. Write the brief with `demo1-agent-brief-writer` Template A. Put the chosen evidence in `## Evidence` and keep the other Top10 items out of scope ("다른 Top10 동시 패치 금지").
6. Reply: `실측:` line, Top10 table, Shortlist 3, THE ONE, the brief (path plus text), then "원하면 다음으로 <2번> 지시서도 바로 뽑아줄게."
7. If the user asks for more ("더 문제 있는 것도"), keep numbering (P7…P13), merge the new items into the same SSOT, and say where they went.

## Guardrails
- Diagnosis is read-only. Fixes belong in the brief for the right agent.
- Mark stale observations with a date. Anything you did not measure goes in `evidence_needed`.
