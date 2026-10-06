# DEMO1-DISPLAY-SEARCH-CAPABILITY-ASSIST-20261006

Codex patches `DEMO1-DISPLAY-SEARCH-CAPABILITY-FALLBACK-20261006`. This file is the assist pointer.

Detail: `var/codex-assist-display-search-capability-20261006/README.md`.
Scanner: `scripts/display_search_capability_assist.py`.

No `AGENTS.md` stub was added. The earlier display assist recorded `agents_md_budget.py check` as `SIZE_OVER`, and this assist does not grow that file.

Product source stays with Codex. At assist close the live lease was `display-capability-timeout-01a10ef1` (GeminiGateway and GeminiGatewayContractTest, expiry 2026-10-06T05:15:44Z). Topic `display-capability-01a10ef1` was no longer on disk. Re-read lease status before a product edit. A scan exit 0 is not a product PASS. Do not force-release a live lease. `effectiveFallbackAllowed()` stays a READ_ONLY getter on the existing `Routing` record.
