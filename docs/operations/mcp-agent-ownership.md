# MCP agent ownership — demo-1 (SSOT)

One-page owner map for MCP servers that touch this checkout (Project Root
`C:\AbandonWare\demo-1\demo-1\src`). Alignment task: `mcp-harmony-config-align-60245890`
(2026-09-24). Prior evidence: `docs/codex-review-mcp-state-report-20260920.md`.

Verification tiers (same vocabulary as the 2026-09-20 report):
`config` = declared in a config file · `loaded` = client tool catalog observed ·
`status-verified` = read-only status call succeeded · `review-verified` =
`mode:review` generation observed. A config entry is never auth or reachability
proof. Env var **names** only below — never values.

## Ownership table

| Agent | Owns | Access | Do NOT |
|---|---|---|---|
| Codex | `awx-control-tower` (dev), `awx-shared` (guarded), `glm_agent`; browser/github via **plugins** | Project Root trusted | enable stale `codex-engine`→AbandonWareX; duplicate filesystem MCP |
| Devin | `codex-review` (= awx stdio + `shared-read`) | read-mostly review | treat Cursor `Codex-Engine` as project MCP; install context7 |
| Grok | `awx-control-tower` aligned to same absolute script + hermes python | match Codex or explicit `shared-read` if review-only | bare `python` + relative args without `cwd`=Project Root |
| Toolbox/skills | `demo1-mcp-control-tower` + `scripts/awx_mcp_toolbox.py` | scripts path | assume MCP server X is connected just because the skill exists |

## Expected demo-1 MCP surface

- **AWX control-tower family** — all spawn this checkout's scripts:
  `scripts/awx_mcp_stdio_server.py` (server root comes from `__file__`, always the
  canonical root regardless of client cwd) or `awx_host_runtime.py run --root
  <Project Root>` wrappers.
- **Optional** `supabase` — single declaration in `src/.mcp.json` with
  `${SUPABASE_PROJECT_REF}` in the URL (env names only: `SUPABASE_PROJECT_REF`,
  `SUPABASE_ACCESS_TOKEN`; OAuth/token still required to connect).
- **NOT expected**: `context7`, a second filesystem MCP, or Codex browser/GitHub
  as MCP servers — for Codex those are plugins, not mandatory MCP.

## Import-surface note (Devin)

Devin Desktop aggregates MCP entries from `%APPDATA%\devin\mcp_config.json`,
`~/.cursor/mcp.json`, and the project `.mcp.json` — a stale Cursor entry loads
inside Devin sessions. `"disabled": true` in any of these files unloads a server
without deleting its declaration; `devin mcp disable <name>` does the same via CLI.

## State after 2026-09-24 alignment

| Server | Config file | Tier | Note |
|---|---|---|---|
| `codex-review` | `%APPDATA%\devin\mcp_config.json` | loaded | hermes python + absolute script + `AWX_MCP_SOURCE_ACCESS=shared-read`; `status-verified` on 2026-09-20, `review-verified` still unobserved |
| `Codex-Engine` | `~/.cursor/mcp.json` | `disabled: true` | was filesystem→`C:/Users/nninn/AbandonWareX` (nonexistent); repeated initialize failures in Devin `MCP Codex-Engine.log` |
| `supabase` | `src/.mcp.json` | config | single declaration kept; `SUPABASE_PROJECT_REF` unset + OAuth refresh failing → `optional-auth-needed`, not a defect to code around |
| `supabase-mcp-server` | `%APPDATA%\devin\mcp_config.json` | `disabled: true` | duplicate of the project `.mcp.json` declaration |
| `git` | `%APPDATA%\devin\mcp_config.json` | `disabled: true` | `optional-auth-needed`: `docker` binary absent |
| `github-mcp-server` | `%APPDATA%\devin\mcp_config.json` | `disabled: true` | `optional-auth-needed`: OAuth flow not completed |
| `exa-code`, `Memory-Vault` | Devin / Cursor configs | loaded | unrelated to demo-1; left as-is |
| `awx-control-tower` | `~/.codex/config.toml` | config | Codex dev surface, unrestricted (29 tools) — unchanged |
| `awx-shared` | `~/.codex/config.toml` | config | `guarded` via `awx_host_runtime.py`, explicit root/state-root — unchanged |
| `glm_agent` | `~/.codex/config.toml` | config | glm delegate/review tools — unchanged |
| `awx-control-tower` | `src/.grok/config.toml` | config | absolute hermes python + absolute `awx_mcp_stdio_server.py` + `shared-read` |
| `codex-engine` | `~/.codex/config.toml` | config | `enabled = false` — stays disabled; stale AbandonWareX args left inert |

## Grok access decision + revert

Grok's `awx-control-tower` was created for the 3-agent review alignment and no
Grok journal records a mutation-tool call (`device_work`, `archive_restore`,
`session_evidence`, `external_evidence_intake`, `desktop_dispatch_packet`,
`producer_kit_export`, `desktop_control_loop`). It is therefore the review/share
path and now carries `AWX_MCP_SOURCE_ACCESS=shared-read` (read-only tools only;
mutations fail closed with `shared_read_mutation_denied`). If a future Grok task
needs the dev surface, add a **separate** unrestricted entry — do not silently
widen this one. Codex keeps its unrestricted `awx-control-tower` because Desktop
Codex owns the dev/dispatch surface.

## Standing rules

- `disabled: true` (or `enabled = false` in Codex TOML) is the reversible off
  switch; prefer it over deleting declarations.
- Skills never prove connectivity — check `mcp list`/logs or make one read-only
  call before claiming `loaded`.
- Supabase env names only in configs/reports; token values stay out of files.
