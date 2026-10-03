# tools/agents — CLI agent runner kit (v0)

One convention for headless CLI agents (agy / grok / codex): prompt via file,
`--mode plan` (read-only) default, `accept-edits` only when explicit,
`--dangerously-skip-permissions`-class flags only when `AWX_AGY_YOLO=1`.

```cmd
Start-Agy-CLI.bat                          :: interactive agy, cwd=project root, no PATH needed
:: (Start-Gemini-CLI.bat removed 2026-09-30 — gemini CLI not installed; use agy)
Start-Agy-CLI.bat -c                       :: resume most recent agy session (-c/--continue, --conversation <id>)
Doctor-Agents.bat                          :: $0 health report (paths/versions/login evidence)
tools\agents\agent.cmd agy review x.md     :: headless run -> data/agent-handoff/agent-runs/
tools\agents\cross-check.cmd <path>        :: 3-way plan-mode review -> verdict.json (3 live calls)
node tools\agents\skill-lint.mjs [--json]  :: $0 SKILL.md frontmatter/index lint; --doctor-line prints "skills: N ok / M warn"
python -B scripts\agent_signal_digest.py   :: $0 fleet signal digest (<2s): leases, journals, handoffs, git, grok asks, events
```

- agy reasoning effort: `--effort low|medium|high|max` — default `high`
  (`AWX_AGY_EFFORT`, user decision 2026-09-30); an explicit `--effort` arg wins,
  `AWX_AGY_EFFORT=off` attaches no flag. `run-agent.mjs` also accepts
  `--conversation <id>` / `--continue` for session resume (agy only).

- Run artifacts per run: `prompt.md`, `stdout.json|.txt`, `stderr.log`, `meta.json`
  (cli/version/args/exit/duration; env var **names** only — never values).
- agy reads `AGENTS.md`/`GEMINI.md` walking up from cwd and auto-loads `.agents/`
  (skills/rules/plugins/mcp_config.json); launchers pin cwd to the project root.
- MCP wiring: project config is `.agents/mcp_config.json` (customization root),
  global is `~/.gemini/config/mcp_config.json`. Currently **not connected** —
  add entries only when the needed token env actually exists; env names only.
