---
name: demo1-demand-driven-external-proof
description: Use when demo-1 tasks mention Browser, Computer, Supabase, MCP auth
---

# Demo1 Demand Driven External Proof

## Core Rule

Browser, Computer, and Supabase are separate evidence lanes. Use them only when the changed surface requires that lane, and never treat helper reachability as proof of source correctness.

## Lane Matrix

| Lane | Default | Required when | Missing proof label |
| --- | --- | --- | --- |
| Browser | optional supporting evidence | UI, route, DOM, stream, or visible chat behavior changed | `browser-smoke-missing` |
| Computer | optional supporting evidence | Windows app/UI automation behavior changed or user explicitly asks | `computer-use-smoke-missing` |
| Supabase | read-only evidence-needed unless project-scoped auth exists | live DB schema/auth/RLS/storage proof is explicitly required | `supabase-project-ref-missing` or `supabase-auth-missing` |
| Mac mini/Notebook | optional supporting producer evidence | explicit multi-node/PatchDrop handoff | `producer-proof-missing` |

## Reference Routing

Read `references/demand-driven-external-proof-reference.md` only for the
Supabase read-only probe, missing-proof labels, Browser/Computer proof hints, or
report template.

## Supabase Boundary

Run only the read-only probe by default. If CLI, project ref, token, or MCP auth
is missing, set `supabase: evidence_needed`, `mutationAllowed: false`, and
`patchScope: local source only`.

Do not create migrations, SQL changes, new Supabase clients, service role usage, or schema assertions without project-scoped read-only proof.

## Browser And Computer Boundary

Use Browser or Computer proof only when it can disprove the changed behavior. Prefer DOM selectors/count-only evidence for Browser and visible Windows state for Computer.

Do not:

- run UI smokes just because the tags appear;
- treat `about:` tabs, installed helpers, or app reachability as success;
- automate Windows terminals through Computer Use;
- transmit secrets, auth prompts, or external data while proving local source behavior.

## Reporting

Keep the lanes separate using the reference report template.

If Desktop proof is green and only Supabase project scope is missing, do not keep patching source. Report the exact missing env/auth artifact.
