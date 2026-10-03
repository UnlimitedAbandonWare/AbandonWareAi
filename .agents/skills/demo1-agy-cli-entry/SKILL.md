---
name: demo1-agy-cli-entry
description: >-
  Use when the Antigravity CLI (agy.exe, Start-Agy-CLI.bat) works inside the
  demo-1 checkout: entry order (AGENTS.md Core Request Entry first, then
  demo1_vibe_skill_router resolve), read-only --mode plan as the default,
  user-requested edits only behind the existing lease/checkpoint gates, and
  file:line + command + observed-output evidence reporting. Also covers the
  tools/agents headless kit and the $0 skill-lint/doctor probes.
---

# demo1 agy CLI entry

What an agy session does on this checkout, in order. `agy` is the Antigravity
CLI (Gemini-CLI lineage); it auto-discovers `AGENTS.md`/`GEMINI.md` and
`.agents/` by walking up from cwd — so it must be launched from the project
root `<repo>`.

## Entry order

1. `Start-Agy-CLI.bat` (pins cwd to the project root; agy is NOT on PATH) or a
   headless `tools\agents\agent.cmd agy <role> <prompt.md>` run.
2. Read the root `AGENTS.md` — the `DEMO1-CORE-REQUEST-ROUTER` block decides
   the primary skill; `python -B scripts/demo1_vibe_skill_router.py resolve "<ask>"`
   resolves exactly one primary (+<=1 optional) via `.agents/skills-intent-index.yaml`.
3. Default posture is **read-only**: `--mode plan`, no edits. `run-agent.mjs`
   never attaches bypass flags unless `AWX_AGY_YOLO=1`. agy is a **pure
   amplifier** (STRICT_ZERO): it reads, judges and designs directives — product
   source edits are delegated to Codex/Devin, never done in-session
   (`agy-korean-grokbot-role.md`).
4. Edits only when the user asked for a change AND the demo-1 gates pass:
   work_journal + codex_work_checkpoint preimage + `__patch_drop__` lease for
   executable/source targets (`$demo1-work-ledger`). `.agents/skills/**.md` and
   docs are lease-free.
5. Report evidence only: `file:line`, the command run, its observed output.
   Unrun checks say `not_observed` / `evidence_needed`, never implied PASS.

## Tool kit (tools\agents, $0 except noted)

| Tool | Purpose |
|---|---|
| `Start-Agy-CLI.bat` | interactive agy, cwd pinned, no PATH needed |
| `tools\agents\agent.cmd agy <role> <prompt.md>` | headless run → `data/agent-handoff/agent-runs/<ts>-agy-<role>/` (prompt.md, stdout, stderr, meta) |
| `tools\agents\run-agent.mjs` | runner core: `--mode plan` default, `--accept-edits` opt-in, `--effort` (agy default `high`), `--conversation <id>`/`--continue` session resume, `--timeout 5m`, env var **names** only |
| `python -B scripts\agent_signal_digest.py [--json]` | $0 fleet signal digest (<2s): live leases, in-progress journals, fresh handoffs, git state, Grok asks, event bus — run before directive design |
| `tools\agents\cross-check.cmd <path>` | 3-way plan-mode cross-check → `verdict.json` — **3 live calls** per run |
| `Doctor-Agents.bat` | $0 health report (paths, versions, auth evidence, `skills: N ok / M warn` line) |
| `node tools\agents\skill-lint.mjs [--json]` | $0 SKILL.md lint: frontmatter, name=folder, description length, intent-index ghosts |
| `agy -p "/skills"` | $0 skill list (print mode runs the slash command locally — no model call) |
| `agy models` / `agy mcp list` | $0 metadata probes used by doctor.mjs |

## Cost rules

- Generation calls (anything except the $0 probes above) spend the **user's own
  Google account quota**. Never buy credits, never switch to a paid model, and
  never enable `useG1Credits`-style billing flags from agent initiative — that
  is an ASK_ONCE boundary.
- "Out of credits" or quota errors: record the error text and stop; do not
  retry-farm or route around it.
- Paid provider APIs (Jev, OpenAI, Groq, …) follow `AWX_AGENT_SPEND_GUARD` and
  `docs/API_ROUTING_SPEC.md` — never called for decorative evidence.
- Watched env keys are `GEMINI_API_KEY` + `OPENAI_API_KEY` only —
  `ANTHROPIC_API_KEY`/`XAI_API_KEY`/`GOOGLE_API_KEY` are unused; do not re-add
  them to check lists (user decision 2026-09-30). Jev keys
  (`AI_GATEWAY_API_KEY`) are owned by `scripts/apikit`, not tools/agents.

## demo-1 hard constraints (still binding on agy)

- Project root is `<repo>`; minimal diff; no secret
  values in logs/commits (env names only); openssl key name/value/format/structure
  immutable; verify verdicts keep run/verified/build/full-verification distinct
  (`awx.debug.verify.v2`).
- Global operating principles live in the `awx-agy-operating` skill (user-global);
  demo-1-specific rules live in the root `AGENTS.md` and the other demo1-* skills.
