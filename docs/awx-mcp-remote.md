# AWX MCP: ChatGPT Pro remote connection

The existing `scripts/awx_mcp_stdio_server.py` remains available for local clients.
The new adapter uses the official MCP Python SDK to expose **Streamable HTTP with
SSE responses at `/mcp`**. It is not a legacy `/sse` + `/messages` server. ChatGPT
supports this transport. Requests use the existing AWX worker queue, validation,
timeouts and shutdown cleanup. Cancelling the running request coroutine forwards
cancellation to its worker. No Java application or Gradle restart is required.

## Authentication choice

ChatGPT web supports OAuth, no authentication and mixed authentication, but **does
not accept a custom API key**. Its OAuth access tokens still arrive as
`Authorization: Bearer <TOKEN>`. The adapter therefore provides:

- `AWX_MCP_API_KEY`: a static Bearer key for CLI/API clients and HTTPS verification.
- `AWX_MCP_OWNER_KEY`: a separate owner key entered in this server's OAuth consent
  page. This enables OAuth discovery, dynamic client registration, authorization
  code + PKCE S256, short-lived access tokens and rotating refresh tokens.
- No unauthenticated tool mode. At least one strong key is required at startup.

For ChatGPT, enable the owner key and choose **OAuth**. Never put an API key in the
MCP URL, the OAuth client ID or client-secret fields, a chat message, or a log.

## Install and local startup (PowerShell)

Run in `C:\AbandonWare\demo-1\demo-1\src`:

```powershell
python -m venv .venv-awx-mcp
.\.venv-awx-mcp\Scripts\python.exe -m pip install -r scripts\requirements-awx-mcp-http.txt
# Create independent process-only keys; these assignments do not print them.
$env:AWX_MCP_API_KEY = & .\.venv-awx-mcp\Scripts\python.exe -c 'import secrets; print(secrets.token_urlsafe(32))'
$env:AWX_MCP_OWNER_KEY = & .\.venv-awx-mcp\Scripts\python.exe -c 'import secrets; print(secrets.token_urlsafe(32))'
$env:AWX_MCP_PUBLIC_URL = 'http://127.0.0.1:8765'
$env:AWX_MCP_ALLOWED_TOOLS = 'build_error_mine'
.\.venv-awx-mcp\Scripts\python.exe scripts\awx_mcp_http_server.py
```

Use a password manager's private entry flow to retain the owner key for entering
the consent form; do not print it into shared terminal/chat history. Alternatively
enter a pre-generated random owner key into the process environment through your
secret manager. Keys must be different, ASCII, 32–256 characters, without spaces.
The server binds only `127.0.0.1:8765`; `--port` can change the port. Update the
public URL's port for local testing when changing it.

Only `build_error_mine` is exposed by default. Its output contains stable failure
classes, not the raw build log. Add existing, reviewed canonical tool names to
`AWX_MCP_ALLOWED_TOOLS`, comma separated, then restart. Each added tool runs with
this Windows account's existing AWX permissions; hints such as `readOnlyHint`
alone do not prove absence of subprocess, provider or artifact effects. No alias
or unlisted tool bypasses the remote allowlist. Resources/prompts are not exposed
by this transport; use the existing stdio client for those capabilities.

## Expose HTTPS with ngrok

### Existing verified domain on this Desktop

The user confirmed **`https://abandonwareai.kro.kr`**. Its existing TLS gateway
validates correctly for that hostname and forwards to `127.0.0.1:80`, preserving
Authorization and setting the public Host header. For this installed gateway:

```powershell
$env:AWX_MCP_PUBLIC_URL = 'https://abandonwareai.kro.kr'
python -X utf8 scripts\awx_mcp_http_server.py --port 80
```

Set the two keys as described above first and verify port 80 has no other owner.
Register **`https://abandonwareai.kro.kr/mcp`**. This arrangement reuses the current
certificate without copying or changing private keys and needs no tunnel. The
different hostname `abandon.kro.kr` does not match that certificate or DNS target.
The gateway's upstream timeout is separate from the AWX worker execution limit.
Do not replace another application's port-80 listener if one is subsequently
started; coordinate its route ownership first.

### ngrok alternative

Install ngrok from its official instructions, sign into your account and configure
its agent credential using ngrok's private setup flow. In a second terminal:

```powershell
ngrok http http://127.0.0.1:8765
```

Copy the HTTPS origin assigned by ngrok. Stop only your AWX adapter with Ctrl+C,
set `$env:AWX_MCP_PUBLIC_URL = 'https://YOUR-NGROK-HOST'`, and restart it with the
same process keys. Register `https://YOUR-NGROK-HOST/mcp` in ChatGPT. Preserve the
incoming public `Host` header; do not use ngrok's host-header rewrite option.
Changing tunnel origin requires restarting the adapter and reconnecting ChatGPT.
An ephemeral tunnel is not a permanent deployment.

## Expose HTTPS with a named Cloudflare Tunnel

**TryCloudflare Quick Tunnels do not support SSE.** Use a named/managed tunnel
with a domain in your Cloudflare account. After installing `cloudflared` and
completing `cloudflared tunnel login` through its browser login:

```powershell
cloudflared tunnel create awx-mcp
cloudflared tunnel route dns awx-mcp awx-mcp.YOUR-DOMAIN
```

Example `config.yml` (replace placeholders locally, do not commit credentials):

```yaml
tunnel: YOUR-TUNNEL-UUID
credentials-file: C:\Users\YOUR-USER\.cloudflared\YOUR-TUNNEL-UUID.json
ingress:
  - hostname: awx-mcp.YOUR-DOMAIN
    service: http://127.0.0.1:8765
  - service: http_status:404
```

Set `AWX_MCP_PUBLIC_URL=https://awx-mcp.YOUR-DOMAIN`, restart the adapter, then run
`cloudflared tunnel --config C:\YOUR-PATH\config.yml run awx-mcp`. Preserve the
public Host header. Avoid an extra Cloudflare Access login challenge on the MCP
or OAuth endpoints unless explicitly integrated with the MCP client. Do not
disable the adapter's Bearer validation. Configure stable service startup through
the tunnel provider's documented service mechanism only after HTTPS tests pass.

## Register in ChatGPT Pro web

1. Enable **Settings → Apps → Advanced settings → Developer mode** if necessary.
2. Create an app with MCP server URL `https://YOUR-PUBLIC-HOST/mcp` and **OAuth**.
   Dynamic client registration is provided by `/register`; manual client secrets
   are not required for that flow.
3. Complete the redirect to **Connect ChatGPT to AWX** on your own HTTPS hostname.
   Enter the separate AWX owner key and authorize the configured tools.
4. The default callback policy accepts only connector-specific HTTPS callbacks
   under `https://chatgpt.com/connector/oauth/`. If the UI assigns another callback,
   copy that **exact** URL into `AWX_MCP_REDIRECT_URIS` and restart. Do not infer a
   wildcard or claim issuer-identification support: this server does not advertise
   `authorization_response_iss_parameter_supported`.
5. Refresh the app tools, attach AWX to a chat, then ask it to classify a known
   synthetic build log with `build_error_mine`. Confirm the actual tool invocation
   and expected class. Listing tools or an HTTP 200 alone is not execution proof.

Discovery: `/.well-known/oauth-protected-resource/mcp` and
`/.well-known/oauth-authorization-server`. The required scope is `awx:tools` and
the OAuth `resource` is the full public `/mcp` URL on authorize and token requests.
Missing/invalid Bearer access receives 401 with a resource-metadata challenge.

## Automatic verification

```powershell
# No real credentials are needed for the isolated acceptance suite.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_awx_mcp_http_auth.ps1 `
  -Python .\.venv-awx-mcp\Scripts\python.exe
# Add a live HTTPS probe from a shell with AWX_MCP_API_KEY already set.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_awx_mcp_http_auth.ps1 `
  -Python .\.venv-awx-mcp\Scripts\python.exe -Url 'https://YOUR-PUBLIC-HOST/mcp'
```

The script prints fixture test results and, when requested, missing/wrong/valid
token status codes. It fails on skipped/zero/failed tests or unexpected probe
results. It writes `build/awx-mcp-http-verification/junit.xml` and `result.json`.
Tokens and response bodies are omitted from its report. A live API-key probe does
not replace the ChatGPT OAuth and tool-invocation acceptance step.
`-ExecutionPolicy Bypass` applies only to this PowerShell child process for this
reviewed script; it does not change the persistent Windows execution policy.

The repository's existing Docker autograder currently targets its own declared
MacSrc root; do not use fixture `ContractTest` to claim Desktop execution. The
script above is a local Python autograder and reports Docker `not_observed`.

## Operating limits and rollback

This adapter is for one owner and one process, bounded to 256 entries per store.
Without `AWX_MCP_OAUTH_STATE_PATH`, its OAuth state remains memory-only.
On Windows, an absolute `AWX_MCP_OAUTH_STATE_PATH` enables atomic CurrentUser
DPAPI storage of clients, access tokens and refresh tokens. The encrypted state
is bound to the public resource and the existing owner key. A second writer,
wrong owner key or unreadable state fails closed without resetting the file.
Consent transactions and authorization codes remain memory-only.
Consent expires in 5 minutes; codes in 60 seconds; access tokens in one hour;
refresh tokens and client registrations in 24 hours. Refresh rotates tokens and
invalidates the replaced access token. Successful refresh extends the existing
client registration to the new refresh expiry. With encrypted state enabled,
established grants survive process restart under the same Windows user, public
origin and owner key. Expired or revoked grants still require authorization.
Old memory-only grants cannot be recovered after their process has exited.

### Same-user startup using the existing public gateway

The launcher defaults to Status and preserves existing credentials. Run Configure
interactively under the Windows account that will run AWX; enter the AWX owner
key into its masked prompt, never a ChatGPT password or a key in command arguments.
It saves a Windows DPAPI-protected credential under `%LOCALAPPDATA%\AwxMcpHttp`.
Do not create a replacement key just to check an existing working connection.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start_awx_mcp_http.ps1 -Action Configure
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start_awx_mcp_http.ps1 -Action Start
```

After the authenticated HTTPS and ChatGPT tool checks pass, enable current-user
logon startup with `-Action InstallStartup`. It creates only the owned
`AWX MCP HTTP.lnk` in the user's Startup folder, refuses to replace a different
shortcut, launches hidden, and never stops an existing port-80 owner. `-Action
Status` reports only stored-state/startup presence and listener count; those
booleans are not authentication or tool-call proof. No PC reboot is performed.

Auth routes have a global limit of 60 requests/minute. Requests are bounded to
16 KiB on auth routes and 1 MiB on `/mcp`; at most 32 HTTP requests and the
existing bounded AWX worker queue are active. Keep it behind a tunnel/reverse
proxy with its own connection and slow-client limits. Access logs are disabled;
do not enable request/header/body logging in the tunnel or another proxy.
Stateless transport intentionally offers no session-resume, cross-request MCP
cancellation notifications, or background SSE GET stream; each POST can receive
its own SSE response. A client abandoning a call still has the worker's 120-second
execution ceiling if its disconnect does not cancel the running request coroutine.

Rollback: stop only the owned adapter, disable only its installed Startup shortcut,
and restore the task's source before-images if needed. Preserve the encrypted
credential/state files and existing ChatGPT registration until their owner makes
an explicit removal decision. The stdio entry point and TLS gateway are unchanged;
the adapter performs no Java/config migration, production database change, commit
or push.

## Official references

- [ChatGPT developer mode and transports](https://developers.openai.com/api/docs/guides/developer-mode)
- [OpenAI MCP authentication and API-key limitations](https://developers.openai.com/plugins/build/auth)
- [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk)
- [ngrok setup](https://ngrok.com/docs/start)
- [Cloudflare Quick Tunnel limitations](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
