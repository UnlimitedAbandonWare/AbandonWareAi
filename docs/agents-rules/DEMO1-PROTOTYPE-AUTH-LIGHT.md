<!-- moved-from: AGENTS.md L392-L400 sha256=bf72e6518797036429b8033bea1d246608e2c727ba91c98fd3bf5f2439beb3eb movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-PROTOTYPE-AUTH-LIGHT -->
## Prototype auth-light mode (PROTO_OPEN)
- **Auth mode = PROTO_OPEN** (flag `demo.auth.proto-open`, env `DEMO_AUTH_PROTO_OPEN`, set in `application-meta-display.yml`): this product is a prototype — login/security expectations are intentionally LOW; do not enforce production auth unless the user says "harden".
- Do not require admin login for operator/debug/admin UI features in the prototype. In `proto-open` mode the existing `AdminTokenGuardFilter` grants `ROLE_ADMIN` to every request, so all `.hasRole("ADMIN")` matchers and the dual AdminTokenGuard (filter + interceptor) pass without tokens — do not add extra role gates or a second token check that would re-block the demo.
- Do not quiz the user about "local vs LAN vs public" scope — treat access as globally prototype-open (local, Fold, private net, and external origins share one open scope for the demo).
- Do **not** disable CSRF as a fix — CSRF/cookie handling stays as-is; prefer the existing session paths, but never let an `AdminTokenGuard`/role gate block prototype access when it breaks the demo.
- Never commit plaintext passwords or secrets — credentials stay env/local-only (`.secrets/`, env vars, gitignored `application-local.yml`); rotate and harden later. Risks + hardening TODOs: `docs/PROTOTYPE_AUTH.md`.
- **Never deploy or push with `proto-open` enabled** — it is a development-only posture; a public deployment with this flag on is a hard stop, not a warning.

### Codex vibe access (user direction, 2026-10-05)
- During vibe coding on the canonical local development server, use the existing `demo.auth.proto-open` / `DEMO_AUTH_PROTO_OPEN` path to access operator/debug/admin surfaces without signing in. When this mode is verified active, do not ask the user to log in or provide an account as a prerequisite for source work or local verification.
- Verify the current mode from the existing configuration and a fresh anonymous HTTP request to the actual target. Do not infer access from a saved browser session. Record the HTTP status and allowlisted request identifier; omit cookies, tokens, authorization headers and private response bodies.
- Keep the existing `AdminTokenGuardFilter` and interceptor behavior. Do not add a duplicate bypass, disable CSRF, or change deployment settings merely to make a browser check pass. This direction does not authorize enabling prototype access on a production deployment.
- If authentication is explicitly under test, report invalid-credential handling separately from prototype access. In verified `PROTO_OPEN`, anonymous or post-logout access can be expected; do not label it as a failed protection test or require blocking as the completion condition.
- User-supplied credentials may be used only for the explicitly authorized login test. Never copy them into instructions, source, reports, screenshots, saved browser authentication state or Git. A login success claim still requires a fresh form submission and observed result.
<!-- END DEMO1-PROTOTYPE-AUTH-LIGHT -->
