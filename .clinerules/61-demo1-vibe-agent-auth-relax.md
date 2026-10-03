---
trigger: always_on
description: Vibe agent auth/privilege relax — login/admin/token gates are agent-N/A under proto-open; OS elevation never demanded; hard security stays (secrets, push/add, proto-open exposure)
---

# Demo-1 Vibe Agent Auth Relax (AGENTS.md `DEMO1-VIBE-AGENT-AUTH-RELAX` companion)

Agent-side only: relaxes what agent rules may demand of a vibe session. It does not change product auth behavior or runtime security. SSOT: AGENTS.md `DEMO1-VIBE-AGENT-AUTH-RELAX`; product posture: `DEMO1-PROTOTYPE-AUTH-LIGHT` (proto-open).

## N/A for the agent while `proto-open` is true
- Admin/operator **login success** is not a Done/verify item; never block delivery on a credential the session was not given.
- **Logout → `/admin` access**: `/login?logout`, anonymous `hasRole("ADMIN")`, and a `200` after logout are **policy**, not defects — never chase them, never add tests to make the old block "green".
- **`X-Admin-Token` / `ROLE_ADMIN` presence**: a lone `403` is not "BLOCKED" and not a patch target.
- **"anonymous must 401/403" RED recipes** and `/admin/**` browser scenarios stay out of CONTINUE/nonadmin work.
- **Permission symptoms** go to PROTO_OPEN + `Read-RAG-Debug.bat` / `var/rag-launcher/LATEST.json` first — never SecurityConfig surgery, a new fail-open matcher (proto-open already exists), an extra AdminToken check, or a second role gate.

## Execution privileges — never demanded
- Source/test/docs/rule edits inside owned scope need **no** privilege elevation.
- Never require or install OS admin elevation, firewall rule changes, SMB share ACL edits, scheduled tasks, or forced Docker image pulls — for the agent or on the user's PC (`DEMO1-PROTOTYPE-LIGHT` no-admin surface).
- "Not enough permission" is handled by changing the route to an owned read-only seam — never by reading `.secrets/` values or by pushing.

## Hard security — unchanged by this file
- Secret/token **value** output, logging, commits: forbidden (names/directories as scope words only; `hasKey`/`keySource`-style evidence).
- `git push`, `add -A`/`add .`, force-push, history rewrite: explicit human decision only.
- Deploying or publicly exposing anything while `proto-open` is on, or turning CSRF off: forbidden (public exposure needs proto-off + human approval).
- Flipping `proto-open=false` as routine hygiene and mass-deleting `Admin*` Java: forbidden — product auth changes are an explicit user decision.
- Foreign leases / bind-scopes / another session's staged paths: request release, never force.
- Paid/external API lanes: ON by default for agent work per the `$demo1-agent-api-spend-guard` SSOT (order `codex_credits → external_paid_api → free_tier → local_ollama`); `AWX_AGENT_ALLOW_PAID_MODELS=0` is the kill switch. Purpose-picked fanout is allowed; identical-probe dedupe and successful-verification replay block stay.

## Supersession and the "harden" exception
- Supersedes, for agent rules only: the login-verify lines of `DEMO1-META-DISPLAY-PLAYBOOK` (P8/P9), `DEMO1-TARGETED-GOAL-LOOP` (G4 login example), `DEMO1-FEATURE-QUALITY-GATE` (auth login/verify), `DEMO1-GPT-PRO` (401/403 RED recipes), the UAW `AdminTokenGuardFilter` fail-closed sections, and ATL-03's "403 without AdminToken" condition — those are reference material, not vibe goals.
- Only when the user explicitly asks to **"harden"/"프로덕션 인증"** may a session target fail-closed auth, proto-off, or SecurityConfig changes; this file never weakens runtime behavior and never authorizes them by itself.
