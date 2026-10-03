# Devin MCP status (2026-10-02 self-heal)

Names, commands, and URLs only. Header and env values stay masked.

## Disabled in `%APPDATA%\Devin\mcp_config.json`

- `git`: command `docker`, args `run -i --rm mcp/git`. Docker is not installed. This server exposes commit, add, and reset, so it stays `disabled: true`. Do not switch it to `uvx`.
- `github-mcp-server`: URL `https://api.githubcopilot.com/mcp`. Disabled to stop the once-a-minute auth retry.
- `supabase-mcp-server`: URL `https://mcp.supabase.com/mcp`. Disabled for the same auth retry.

## Re-enable (user login)

1. Reload the Devin window.
2. Open the MCP panel and sign in to the one server you want.
3. Set that server's `disabled` field to `false` in `mcp_config.json`.
4. Confirm the MCP log says the server connected. Leave `git` disabled.

## Left enabled

- `exa-code`: URL `https://mcp.exa.ai/mcp`. The 2026-10-01 window log already showed a successful connect.
- `codex-review`: command is the hermes `python.exe` launching `scripts\awx_mcp_stdio_server.py`. That window log also showed a successful connect.

## Defined outside mcp_config.json

- `Codex-Engine`: command `npx -y @modelcontextprotocol/server-filesystem` with directory `C:\Users\nninn\AbandonWareX`. A 10-second local start exited 1 because that directory is missing (`None of the specified directories are accessible`). The editable definition is already disabled and already points at `C:\AbandonWare\demo-1\demo-1\src`. The running window still has the old arguments until reload. Status: NEEDS_RELOAD. Do not add a second entry.
- `Memory-Vault`: command `npx -y @modelcontextprotocol/server-memory`. It connected once. No edit.
- OAuth server `supabase`: a stored credential file exists under `%APPDATA%\Devin\mcp\oauth\`. It was not opened and not deleted. Login stays with the user. Status: HOLD.
