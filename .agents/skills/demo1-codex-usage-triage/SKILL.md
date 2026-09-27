---
name: demo1-codex-usage-triage
description: Use when demo-1 work asks to reduce Codex usage, repeated scans
---

# Demo1 Codex Usage Triage

## Core Rule

Spend tokens and tool calls on decision-changing evidence. Prefer cached summaries, count-only reports, exact failure classes, and artifact paths over repeated full scans or raw log dumps.

## Reference Routing

Load details only for the current need:

| Need | Reference section |
| --- | --- |
| Find high-cost loops | `Cost Scan` |
| Choose a small default change | `Patch Candidate Labels` |
| Prove the contract | `Verification Commands` |
| Keep reports compact | `Reporting Fields` |

Reference file:
`references/codex-usage-triage-reference.md`

## Default Decisions

Prefer small changes that make costly work opt-in:

- keep `write_dispatch=false` unless producer handoff files are requested;
- keep `requireProducerBundles=false` for Desktop-only mode;
- keep `externalEvidenceMode=optional` unless explicitly required;
- report Browser, Computer, Supabase, Mac mini, or Notebook gaps as supporting missing evidence;
- use artifact paths for large logs, source scans, trace snapshots, and debug records.

Do not hide real missing evidence as success. Change the default next action, not the truth of the missing proof.

## Stop Rules

Stop local patching when:

- `goal_next_auto` or completion audit says local control tower is ready;
- the next action is only `set_SUPABASE_PROJECT_REF`, MCP OAuth, or producer sidecar collection;
- the same external-only blocker repeats after fresh output dirs;
- the next patch would add another orchestration framework, agent broker, or background daemon.

Use:

```text
no_local_source_action_external_evidence_needed
nextAction=none_for_desktop_only
```

## Verification And Reporting

Use `Verification Commands` and `Reporting Fields` in the reference file. Run
Gradle only when source or build contracts changed, with Desktop cache
isolation.
