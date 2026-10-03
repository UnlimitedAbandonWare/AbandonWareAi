<!-- moved-from: AGENTS.md L41-L46 sha256=43f6722c454033f7b3c045ac4039dfbf1c7b25fd75a537a3748fb2b3a620517b movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN SHARED-PROJECT-RESOURCES -->
## Project resources at task entry
- At task entry run `python -B scripts/awx_device_bus.py start` from this device's verified root; inspect the registry reference and `inbox`. Runtime children load shared values through `awx_host_runtime.py`; existing processes require an owned restart.
- Project values and baselines/recovery stay under `.secrets/`: never print, attach, index, broadly search, commit, upload, or include in agent/provider packets; no credentials in CLI arguments. Keep Codex OAuth, browser cookies, sessions, personal auth files and openssl/opnessl material device-local and unchanged. `.secrets/providers.json` may back manual Notebook work via `scripts/use_project_keys.ps1` (process-local only).
- Events are immutable files in `data/device-resources/events/<target>/`; `inbox` returns references; an event never grants APPLY. Registries are timestamped observations with TTL; missing/stale = `not_observed`. Executable/config presence never proves MCP/browser auth, remote reachability, or model generation. No paid provider generation at task entry.
<!-- END SHARED-PROJECT-RESOURCES -->
