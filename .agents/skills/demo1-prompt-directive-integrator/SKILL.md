---
name: demo1-prompt-directive-integrator
description: Use when an uploaded, pasted, or Mac/Notebook-produced demo-1 patch directive
---

# Demo1 Prompt Directive Integrator

Use this skill for prompt-pack and governance artifacts, not runtime Java
patching. Attached directives are risk maps; live Desktop files and generated
prompt output are authority.

## Trigger Boundary

Use this for `codex_patch_directive_*` files, 9-hour prompt packs, backlog
overlays, scorecards, previous reports, prompt-only handoff artifacts,
`agent-prompts/**`, generated prompt output, manifest changes, or short AGENTS
pointers. Switch to runtime Safe Patch skills for `main/java`, resources,
Gradle, or application behavior.

## Reference Routing

Read `references/prompt-directive-integrator-reference.md` only for UTF-8
intake, requirement extraction, prompt seam search, patch rules, verification
commands, completion reporting, or common mistake checks.

## Operating Contract

1. Read attachments as UTF-8 before searching source.
2. Extract only durable requirements: prompt id, backlog IDs, target files,
   trace keys, focused gates, non-goals, `no_patch_needed`, and
   `evidence_needed`.
3. Confirm the repo-owned prompt seam before editing.
4. Patch the smallest prompt source file that owns the behavior. Keep long
   workflows in `agent-prompts`; update `AGENTS.md` only as a short pointer.
5. Compare generated output through the manifest merge order when traits are
   involved.
6. Keep runtime claims out of prompt packs unless the directive includes exact
   verification gates.
7. Report a proof-backed no-op when live prompt source and generated output
   already contain the directive contract.

## Evidence Boundaries

- Mac mini, Notebook, Browser, Computer, and Supabase proof remains supporting
  evidence unless current Desktop output proves the lane.
- Prompt rebuild proof does not prove the full 9-hour runtime source goal.
- Use count-only secret scans and artifact paths, never raw prompts, raw
  external payloads, auth headers, cookies, DB URLs, or environment dumps.

## Common Verification

Use `Verification Commands` in the reference for skill validation, prompt
build, manifest parse, output comparison, secret checks, and Gradle governance
gates.
