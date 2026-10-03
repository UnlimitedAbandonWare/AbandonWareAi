# MCP call templates — verified shapes (2026-10-03)

Copy the shape, swap values. Evidence: 3-day Codex session scan
(`data/agent-handoff/devin-skill-friction-8eeb784c/baseline.json`).

## Exa — `web_search_exa` (server: `codex_apps` / `exa-code`)

`objective` is **required** — omitting it fails schema validation (observed 2×).

```json
{
  "query": "Playwright locator wait_for load-state semantics",
  "objective": "find the official docs page that defines wait_for/load-state defaults; ignore blog posts; extract the default timeout value",
  "numResults": 5
}
```

## Playwright / cua_repl (`server: cua_repl, tool: js`)

- Wait for **load state first**, then the selector — never click against a
  mid-load DOM. Observed selector deadline ≈ **3 s** (iab browserUse
  `selector deadline exceeded`, `dispatchMouseEvent` 2,992 ms timeout).
- On failure: retry the step **once** after a `waitForLoadState`, then stop
  and report — no selector spam.
- HTTP-probe timeouts elsewhere: `web_repro_matrix.py --timeout` default
  **10 s** (HTTP connect), Debug-RAG verify uses its own per-check timeouts.

```js
await tab.playwright.waitForLoadState("domcontentloaded");
await tab.playwright.getByRole("button", {name: "…"}).click(); // retry once on deadline
```

## AWX control tower (`build_error_mine` etc.)

- `app is not connected` / `USER_NOT_LOGGED_IN` → the session is logged
  out. Do **not** attempt re-login. Fall back to the local scripts lane:
  `python -B tools/build_error_miner.py scan --in <log> --out <pfx>` or the
  project's `scripts/awx_*` equivalents.

## GitHub connector (`fetch_commit`, `codex_apps`)

- HTTP 422 = bad `commit_sha`/`repo_full_name`. Verify the sha is a full
  40-hex (or a ref the API accepts), retry **once** — do not hammer.

## glm_agent (`glm_delegate_task`)

- `context` field capped at **1,024 chars** — pack tight, put bulk context
  in a file path, not inline. (Owned by the GLM v2 directive — pointer only.)
