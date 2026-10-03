<!-- moved-from: AGENTS.md L130-L133 sha256=eacf6cf60e7a12200a2e9ae373a464e19bbab5fadedd43137918d1ccdf82f126 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-BRAVE-DUAL-KEY -->
## Brave dual-key (Free then Base)
- `BRAVE_API_KEY_FREE` first up to `gpt-search.brave.monthly-quota`; then same-host Brave **base** via `BRAVE_API_KEY` only — free-quota exhaustion promotes to base, never disables Brave nor jumps to Naver. `BRAVE_SUBSCRIPTION_TOKEN` is a retired env name (never read/alias/fail over); keep header `X-Subscription-Token` with the selected key; `NAVER_*` is not Brave. Contract: `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md`. Numbers: `docs/volatile-knobs.md` (re-read; stale skill numbers lose).
<!-- END DEMO1-BRAVE-DUAL-KEY -->
