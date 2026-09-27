---
name: demo1-tool-placement-scan
description: Advisory second opinion — ranks already-existing script/bat/skill calls for the current ask plus journal/lease/dirty state and flags known router misroutes; never a new router or executor
---

# demo1-tool-placement-scan

Read-only advisor for **which existing tool to call first**. It prints a ranked
JSON list of already-existing script/bat/skill calls — it never executes them,
never replaces `demo1_vibe_skill_router.py` (it invokes `resolve` as a
subprocess and reports the result verbatim), and grants no write authority.

## Commands

```powershell
python -B scripts/demo1_tool_placement_scan.py --root . scan "<ask>"   # ranked placements + misroutes[]
python -B scripts/demo1_tool_placement_scan.py list-triggers           # audit the trigger table
```

Flags: `--skip-state` (no journal/lease/dirty reads), `--no-router`, `--no-git`,
`--agent <name>` (splits owned/foreign in_progress journals).
Exit codes: 0 ok / 2 usage / 6 evidence-io. Note: the first positional is the
action — a bare `"<ask>"` without `scan` exits 2.

## What the JSON adds over the router

- `placements[]`: ranked existing calls — the known-correct entry point first.
- `misroutes[]`: present when the resolved router primary is a known-wrong
  target for this ask — **prefer the listed `useInstead` calls** over the
  router primary.
- `state`: stale in_progress journals (>24h idle), expired leases, dirty count.

## Trigger → correct first call (measured)

| Ask shape | Router may resolve | Correct first call |
|---|---|---|
| ForceRestart / restart Meta Display | `demo1-meta-display-simple-caption` | `$demo1-dev-reload` / `Start-RAG.bat` |
| RAG debug trail / launcher failure | `demo1-evidence-debugging` | `Read-RAG-Debug.bat` → `var/rag-launcher/LATEST.json` |
| SelfAsk ownership / orphan planner | `demo1-evidence-debugging` | `SelfAskPlannerOwnershipContractTest` + canonical `main/java/.../SelfAskPlanner.java` |
| zombie / stale journal | `demo1-safe-cleanup` | `work_journal.py list --active` + `agent_recovery_status.py status` |
| commit dirty / staged scan | (router null) | `$demo1-conditional-local-git` / `agent_git_vibe_commit.py` |
| goal complete / done criteria | (router null) | `$demo1-goal-complete-stop` |
| verify all models / API spend | (router null) | `$demo1-agent-api-spend-guard` |
| lease conflict | (router null) | `lease_conflict_autoflow.py plan --goal-files ...` |

## Do not

- Treat the ranking as execution — run the listed call yourself; `advisoryOnly` is literal.
- Extend it into a second router or executor — new intents belong in `.agents/skills-intent-index.yaml`.
- Skip work-ledger (journal → checkpoint → verify) or lease gates because a call ranked first.

## Related

- AGENTS: `DEMO1-TOOL-PLACEMENT-SCAN`, `DEMO1-VIBE-SKILL-ROUTER`, `DEMO1-WORK-LEDGER`
- Source: `scripts/demo1_tool_placement_scan.py`, tests `scripts/test_demo1_tool_placement_scan.py`
- Brief: `agent-prompts/tool-placement-scan-20260926/brief.md`
