# Codex OAuth credits and runtime specs (SSOT)

```yaml
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation + live_repo_contract"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "내부 런타임 하드 락(LangChain4j 1.0.1 등) 및 아키텍처 계약으로 외부 API 변동에 독립적인 상시 문서"
runtimeEnforcement: "unchanged"
disclaimer: >
  This page pins the project's Codex/Auth credit order, ChatGPT OAuth lane
  contract, and fixed library/runtime spec values. Token file paths and env
  names are identifiers only — secret values are never recorded here.
sourceUrls:
  - "https://github.com/langchain4j/langchain4j/releases/tag/1.0.1"
  - "https://central.sonatype.com/artifact/dev.langchain4j/langchain4j-bom/1.0.1"
```

## 1. Agent cost order (Auth codex credits first)

Agent sessions spend in this order (AGENTS.md Core auto rules +
`configs/agent-api-spend-guard.yaml`, SSOT `docs/API_ROUTING_SPEC.md` env table):

```
codex_credits (ChatGPT OAuth) → external_paid_api → free_tier → local_ollama
```

- `AWX_CREDIT_BUDGET=62500` — ChatGPT-plan credits authorized 2026-09-30.
  An allocation input, **not** an observed balance; warn-log at 50,000
  cumulative, the authorized budget is the hard bound.
- `AWX_AGENT_SESSION=<taskId>` — required by the Jev gateway spend ledger
  (`scripts/apikit/jev_ledger.py`); a live send without it is refused
  `budget_refused:session-missing`.
- `AWX_AGENT_ALLOW_PAID_MODELS` — kill switch (`0`/`false`/`no`/`off` blocks
  paid lanes). Paid calls are ON by default for agent sessions.
- Vercel AI Gateway USD is a **separate** budget (Jev evaluation only —
  `docs/provider-limits/vercel-ai-gateway-limits.md`).

## 2. ChatGPT OAuth lane contract

- Token file: `.secrets/chatgpt_oauth_credentials.json` (env
  `CHATGPT_OAUTH_CREDENTIALS` overrides the path). File contents are never
  printed, logged, or copied into docs.
- Live E2E check: `python -B scripts/chatgpt_oauth_flow.py test-smoke`
  (exit 0 = valid token, one real call).
- Model catalogue: `data/agent-handoff/chatgpt-oauth/CODEX_MODEL_MATRIX.md`
  (role map; e.g. `chatgpt-oauth:gpt-5.5` = main `/chat` default).
- Route-id rule: `chatgpt-oauth:` ids are not remote-looking, so they work
  under `app.ai.allow-remote-model-selection=false`
  (`docs/agents-rules/DEMO1-MODEL-DEFAULT-AUTH-FIRST.md`).
- Failure contract: `reasonCode=chatgpt_oauth_*` → no same-request retry;
  drop to the next cost-order tier exactly once (401/403/429 never retried).
- `chatgpt-oauth:codex-auto-review` is not a chat model — excluded from
  pickers and defaults.

## 3. LangChain4j pin — `1.0.1` (hard lock)

- `build.gradle.kts:134-135`: `dev.langchain4j:langchain4j:1.0.1` +
  `dev.langchain4j:langchain4j-open-ai:1.0.1`.
- `build.gradle.kts:767-773`: `resolutionStrategy.eachDependency` forces
  `useVersion("1.0.1")` on every `dev.langchain4j` resolution.
- AGENTS.md rule: keep every `dev.langchain4j` dependency exactly on `1.0.1`;
  stop and report if Gradle evidence shows mixed, beta, or non-`1.0.1`
  LangChain4j versions.
- Upstream: `1.0.1` released 2025-05-20 (GitHub tag / Maven Central
  `dev.langchain4j`). Do not bump, float, or mix `1.0.0-beta*` lines.

## 4. Ollama model lock (local last)

- Local Ollama is the **last** tier of the cost order, not a default.
- Installed-model allowlist + role defaults live in
  `docs/API_ROUTING_SPEC.md` "Ollama"; policy pointer
  `docs/agents-rules/DEMO1-OLLAMA-MODEL-LOCK.md`. Live truth: `ollama ls`.
- Verify: `powershell -NoProfile -File scripts/check-model-lock.ps1`.
- Never silently pull a tag or Spring-default a banned tag; use the spec's
  dead-tag→installed alias map.

## Maintenance

- 90-day review cadence shared with `docs/provider-limits/` docs; expiry
  detection: `python -B scripts/verify_provider_limits_freshness.py --check`
  (this file lives outside that scanner's default dir — run with
  `--root docs/specs` when reviewing it).
- Spec values are mutable per `docs/agents-rules/DEMO1-MUTABLE-SPEC-POLICY.md`:
  re-read SSOT files before implementing; only hard constraints are constants.
