<!-- moved-from: AGENTS.md L392-L400 sha256=bf72e6518797036429b8033bea1d246608e2c727ba91c98fd3bf5f2439beb3eb movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-PROTOTYPE-AUTH-LIGHT -->
## Prototype auth-light mode (PROTO_OPEN)
- **Auth mode = PROTO_OPEN** (flag `demo.auth.proto-open`, env `DEMO_AUTH_PROTO_OPEN`, set in `application-meta-display.yml`): this product is a prototype — login/security expectations are intentionally LOW; do not enforce production auth unless the user says "harden".
- Do not require admin login for operator/debug/admin UI features in the prototype. In `proto-open` mode the existing `AdminTokenGuardFilter` grants `ROLE_ADMIN` to every request, so all `.hasRole("ADMIN")` matchers and the dual AdminTokenGuard (filter + interceptor) pass without tokens — do not add extra role gates or a second token check that would re-block the demo.
- Do not quiz the user about "local vs LAN vs public" scope — treat access as globally prototype-open (local, Fold, private net, and external origins share one open scope for the demo).
- Do **not** disable CSRF as a fix — CSRF/cookie handling stays as-is; prefer the existing session paths, but never let an `AdminTokenGuard`/role gate block prototype access when it breaks the demo.
- Never commit plaintext passwords or secrets — credentials stay env/local-only (`.secrets/`, env vars, gitignored `application-local.yml`); rotate and harden later. Risks + hardening TODOs: `docs/PROTOTYPE_AUTH.md`.
- **Never deploy or push with `proto-open` enabled** — it is a development-only posture; a public deployment with this flag on is a hard stop, not a warning.
<!-- END DEMO1-PROTOTYPE-AUTH-LIGHT -->
