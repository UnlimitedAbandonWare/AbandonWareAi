<!-- moved-from: AGENTS.md L140-L148 sha256=65a4b8ad3617607a1183307cf3f298f7b7bf3f1053ced78a04ba19567598e26b movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-PROVIDER-LIMITS-SSOT -->
## Provider limits SSOT
- Entry point: `docs/provider-limits/README.md` (purpose, evidence tiers, refresh rules, runtime-conflict note).
- Per-provider pages: `docs/provider-limits/groq-limits.md`, `gemini-limits.md`, `openai-api-limits.md`, `vercel-ai-gateway-limits.md`, `naver-search-limits.md`, `brave-search-limits.md`, `tavily-limits.md`.
- **90-day TTL contract (2026-10-05)**: each doc's frontmatter carries `ttlDays: 90` + `expiresAt` (last valid day, Asia/Seoul). Past `expiresAt` the doc is **STALE_DISCARD** — do not cite it even if `status` still reads `ACTIVE`; `python -B scripts/provider_limits_janitor.py` (`check`=exit1 on expiry, `status`=per-doc table, `archive`=move to `docs/provider-limits/archive/` + watermark) owns the lifecycle. Docs without `expiresAt` are `NO_TTL` warnings, not `check` failures.
- Do **not** quiz the user about plan/tier/credits when docs are present and `capturedAt` is <90 days old. Unknown account details (`accountPlan`, `accountExactLimits`, remaining credits) are a normal completion state; do not turn them into login/Billing/console prompts.
- 90 days is a project review cadence, not a provider guarantee or a runtime admission proof TTL. Missing docs, stale dates, 429/401/403, model access failures, or proof expiration do **not** authorize browser install, console login, plan quizzes, or credential re-provisioning.
- These docs are read-only SSOT only; they are not a runtime hard cap and do not replace `GroqFreeTierGuard.java`'s 24-hour account evidence check, spend-guard settings, or routing YAML values.
- Legacy link `docs/provider-limits/groq-free-limits.md` redirects to `groq-limits.md`.
<!-- END DEMO1-PROVIDER-LIMITS-SSOT -->
