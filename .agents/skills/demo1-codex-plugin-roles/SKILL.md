---
name: demo1-codex-plugin-roles
description: Use when a demo-1 Codex task must limit enabled plugins per work type
---

# Demo1 Codex Plugin Roles

Codex must never enable every plugin at once. Pick the work type below and
enable only its "on" lanes; everything else stays off. An unlisted or unknown
plugin = not used. A plugin tag grants no source, Git, auth, or deployment
authority — AGENTS.md ownership, lease, preimage, spend, and redaction gates
still apply on top of this matrix.

The tool catalog itself (what exists, upstream sources, call shapes) stays in
`EXTERNAL_SKILLS.md` + `demo1-mcp-control-tower/references/external-skills.md`.
This skill owns only the work-type allowlist and the per-plugin contract.

## Work type → plugin lanes

| Work type | On | Off / limited |
| --- | --- | --- |
| Display / voice / reconnect | Superpowers, Browser (only Display·phone-test·assistId/epoch repro named by the goal), Computer (Fold/console only), AWX (on build failure), GitHub (auxiliary), glm_worker (post-patch rebuttal) | admin/HOLD forced repro as a fixture, Ads, Supabase unless the goal names it, Sites sprawl |
| Jev / AI Gateway | Superpowers, Vercel (auth·env·quota·evaluate docs only), Exa (official docs only), AWX, glm_worker | Vercel deploys/config changes, Ads, Browser admin repro |
| Chat / RAG / Security | Superpowers, Browser (only that goal's HOLD/backend/admin scenario), GitHub (related diff), Exa (Spring Security official docs), AWX, glm_worker | Vercel when unrelated, Meta Wearables |
| General Java edit | Superpowers, AWX, GitHub, glm_worker, Computer (only if a Windows UI fact is decision-changing) | Browser is never mandatory |

## Per-plugin contract

- **Superpowers** — supporting process only (`$demo1-superpowers-repo-evidence-guard`):
  systematic-debugging, one root cause, reproduce/probe before patching,
  verification-before-completion, no duplicate layers. Repo evidence outranks
  its templates.
- **Browser** — only the repro the goal text or target file names. Rendered UI
  text is not success. Use a fresh session; a saved login never counts as
  success evidence.
- **GitHub** — status / HEAD-vs-remote comparison only. No commit, push, merge,
  or branch delete unless the user explicitly asks; local commits go through
  `agent_git_vibe_commit.py` (`$demo1-conditional-local-git`) — never ad-hoc.
- **Exa / web research** — official vendor domains only; never port blog code
  into Java 17 / LangChain4j paths; record check date + version.
- **AWX toolbox** — failure logs route to `build_error_mine` only; never run
  recovery AWX calls on a healthy path (`$demo1-mcp-control-tower`).
- **Computer** — localhost, Fold console, or local Windows UI only; never use
  GUI automation for source exploration (grep/read tools own that).
- **glm_worker** — one post-patch rebuttal pass; agreement is not verification
  success. Availability gating stays `$token-efficient-agents` / `$glm-offload`;
  never re-probe past a transport HOLD.
- **Vercel** — Jev/Gateway work only: read-only docs, auth, env presence, quota,
  evaluate metadata. No deploys, no config changes, no env-file commits, no key
  values. 401/403 = `auth-blocked`; never enable live lanes.
- **ChatGPT Ads Manager / Visualize / Data / Sites / Plugin Management /
  Supabase / Meta Wearables** — off by default; only when the goal names them,
  minimal scope. Supabase stays read-only under
  `$demo1-demand-driven-external-proof`.

## Shared limits

- Never send or commit secrets, cookies, tokens, or `.secrets/` content to any
  plugin or Git lane.
- Reachable ≠ connected; enabled ≠ authorized; helper output ≠ target verified.
  Keep `evidence_needed` / `auth-blocked` / `not_observed` labels distinct.
- No paid-provider fanout; pick the cheapest adequate lane
  (`$demo1-agent-api-spend-guard`).

## Invocation

The user pastes one line instead of a plugin paragraph — canonical copy lives in
`agent-prompts/codex-plugin-roles-shortcut.md`:

```text
@demo1-codex-plugin-roles 이번 작업에서 플러그인 역할 제한해서 써. 플러그인은 전부 쓰지 마.
```

Update the matrix here only — not in AGENTS.md, chat pastes, or per-task rules.
