---
name: demo1-mcp-control-tower
description: Use when coordinating external Codex tasks, Desktop, Notebook
---

# Demo1 MCP Control Tower

Route external Codex tasks and multi-node work through the available app tools
and the existing repo-local MCP-style toolbox. The Korean entry page is
[외부 스킬 · 장비 제어 안내](../../../EXTERNAL_SKILLS.md).

## Core Contract

- Desktop owns canonical source and final verification.
- Mac mini and Notebook PatchDrop producers use separate worktrees or clones
  and provide supporting producer evidence only.
- An explicitly authorized Notebook direct-source request routes to
  `demo1-macsrc-smb-direct-patch` on verified canonical `Y:\` under root
  `AGENTS.md`; this hub supplies no direct-write authorization of its own.
- Desktop final apply plus Gradle or boot proof stay authoritative.
- Missing producer proof is not a Desktop-only artifact failure.

## Reference Routing

| Task | Read only the relevant reference |
| --- | --- |
| Choose external skills, app tools, research, Browser/Computer, Sites or analytics | [external-skills.md](references/external-skills.md) |
| Check a host, coordinate Codex tasks, hand off Notebook/Mac mini work | [node-playbook.md](references/node-playbook.md) |
| Read current shared API keys/settings, refresh the Desktop snapshot, or verify Notebook recognition | [project-resource-context.md](../../../docs/project-resource-context.md) |
| Exact toolbox schemas, command cookbook and PatchDrop verification | [control-tower-reference.md](references/control-tower-reference.md) |
| Automatic tool preparation for a Notebook directive patch request | [Desktop intake tool readiness](../demo1-desktop-canonical-goal-intake/references/tool-readiness.md) |

Read `main/resources/mcp/awx-control-tower-tools.json` for fixed JSON schemas.
Invoke tools through `python .\scripts\awx_mcp_toolbox.py --input-json - tool`
when stdin JSON avoids quoting drift.

Read `references/control-tower-reference.md` only when you need exact command
examples, archive rules, producer bundle rules, the full tool map, or
verification command variants.

Managed setup: `scripts/awx_mcp_node_setup.py`; use the commands in
[the node playbook](references/node-playbook.md). Generated configs set `cwd`
to host-local AWX state and pass the source root explicitly to
`awx_host_runtime.py`. Producer source remains an independent worktree or clone;
explicit shared-read access grants no source mutation authority. The static
`main/resources/mcp/awx-control-tower-mcp-client.sample.json` is a legacy sample.

## Select A Control Surface

For shared API/DB/tool context, read the resource reference above. The user-selected
direct snapshot is `.secrets/providers.json`. Desktop refresh uses
`python -B scripts/awx_project_keys.py refresh --apply` within an authorized refresh
task; existing entries and recovery are retained. In a Notebook PowerShell, load
`. ./scripts/use_project_keys.ps1`, then run `python -B scripts/awx_project_keys.py check`
in the same shell before launching the requested consumer. Never print/export
values into agent context. Report name/count recognition separately from provider
generation, automatic SMB security, and the peer's actual acknowledgement.

1. Preserve the requested outcome, target host/project/task, exact path scope,
   mutation authority and required acceptance evidence. Refresh task-relevant
   live tool availability; a tag or installed skill is not a connected host.
2. For app tasks, use `list_projects` and `list_threads`, then the returned IDs
   with `read_thread` or `wait_threads`. Create, message or hand off a task only
   when the user's request authorizes that operation. A subagent is a bounded
   worker in this task, not a Notebook or Mac mini connection.
3. For repo tool work, select one primary route below. For a requested full-load,
   discover the complete current tool catalog and prepare the applicable schemas,
   skills, and owned runtime session through the Desktop intake reference above.
   Load external skills from the current session catalog as their task boundary
   becomes relevant; do not copy versioned plugin caches into repo skill folders.
4. Keep availability separate: file present, tool callable, target connected,
   operation authorized, operation verified. Missing evidence stays
   `evidence_needed`; delivery alone never proves the task's required meaning.
5. Use `token-efficient-agents` and `glm-offload` for bounded delegated reads.
   Honor the current user/project transport HOLD without repeated GLM probes;
   keep writes and final judgment in the parent. Never infer availability from
   a key-presence check alone.

## Safe Flow Tokens

Flow contract: Broad probe -> Focused probe -> Minimal diff -> Desktop
verification -> Failure classification -> Retry.

1. Establish Desktop truth with `source_scan` or a no-write
   `desktop_control_loop`.
2. Export producer kits only after an explicit multi-node producer assignment.
3. Use focused source reads, `archive_search`, or current failure evidence.
4. Use `patch_plan` and `patch_render` for the smallest PatchDrop candidate.
5. Intake/audit copied proof with external evidence tools.
6. Apply only through PatchDrop janitor gates, then run Desktop verification.

Failure classification uses `build_error_mine` and tool `failReason` fields.
Retry once per unchanged failure class. If the class does not change, require
new evidence before more tool calls.

## Boundaries

- No new SMB service, shared-source edit loop, or background broker.
- No Mac mini or Notebook PatchDrop producer writes to Desktop canonical root;
  the explicit Notebook direct route remains owned by its existing guard.
- Dispatch packet and producer kit writes are opt-in; keep Desktop-only checks
  read-only by default.
- No raw build logs or secrets; use `build_error_mine`, `failReason`, and
  env-name-only evidence.

## Verification

For control-tower behavior changes, use `Verification Commands` in the
reference file. For skill-only postprocessing, validate the skill and family
report through `demo1-skill-family-postprocessor`.


## Personal Codex subscription review

Use `codex_review_change` only when a concrete independent counterexample or
design check remains after the current worker and existing agents. Supply a
review reason and bounded excerpts; relative paths are labels, never file-read
instructions. `status` consumes no generation; `review` consumes the same
ChatGPT subscription once and accepts no arbitrary model/provider/command.
For short bounded reviews, prefer `reviewProfile=economy`: the pinned
`gpt-5.3-codex-spark` uses the Pro preview's separate usage limit. Check its
current availability first. `reviewProfile=quality` (the compatible default)
retains `gpt-5.5` for deeper review. Neither profile falls back to another model.
The pinned official CLI App Server uses an empty environment, unchanged official
model metadata and delegation disabled. The normal host policy instructions remain.
Status-only requests, trivial explanations and repeated confirmation need zero
review calls. A substantive request such as "review a missed counterexample"
selects one call only when those conditions hold. Source changes remain owned
by the parent. Require a matching terminal, valid findings, evidence-ID closure
and cleanup; worker success does not establish remote provider/wire proof or a
specific Pro model entitlement. Unknown usage stays null and unobserved remote
lineage stays HOLD.

The deadline also bounds a child that stops reading stdin; stop its owned
process before joining the input writer. Keep timeout/cancellation reasons when
the terminated pipe closes. Do not re-register an already configured server to
check availability: inspect its entry and use status. The official `mcp add`
command rewrites the MCP table. For future registration, compare before/after
objects in memory and retain only hashes and redacted structural differences;
never persist raw configuration/auth values or reconstruct missing history by guess.
