---
name: demo1-mutable-spec-policy
description: Use before demo-1 API/model/Display/routing/port implementation or patches — mutable spec values are re-read from SSOT; only hard constraints are constants
---

# Demo1 Mutable Spec Policy

This repo's specs change often. Old comments, skills, handoffs, and examples
still carry outdated values. Treat spec numbers/names as **variables resolved
from SSOT at work time**, never as constants baked into code. The only true
constants are the hard constraints.

## When

- Before implementing or patching anything that touches API/provider routing,
  Ollama model tags, ports, Meta Display lengths/timings, Start-RAG profiles,
  or UI copy.
- Before trusting a number, model tag, endpoint, or flag found in a comment,
  skill body, handoff, blog, or old example.
- When a local doc or skill disagrees with `configs/api-routing.yaml`,
  `docs/API_ROUTING_SPEC.md`, or a live probe.

## Do

1. Locate the SSOT for the touched area (`docs/MUTABLE_SPEC_POLICY.md` table):
   - API / models → `configs/api-routing.yaml` + `docs/API_ROUTING_SPEC.md`;
     live check `ollama ls` or `scripts/check-model-lock.ps1`.
   - Display runtime → AGENTS.md `DEMO1-META-RAYBAN-DISPLAY-RUNTIME` +
     `main/resources/application-*.yml` + saved Fold `lensSettings` prefs.
   - Restart / ports → `$demo1-dev-reload`, `scripts/start_rag_stack.ps1`;
     live check port `READY` / `var/rag-launcher/*/result.json`.
   - Skills / handoffs → current `.agents/skills/*/SKILL.md` body, never an
     old copy.
2. On drift between prose and SSOT/live, SSOT/live wins: fix the spec/YAML or
   conform the code to it first, then demote the stale prose in the same task
   (same order as `$demo1-api-spec-drift-guard`).
3. To change a value, edit its single SSOT and keep call sites reading
   config/YAML — no literal fan-out.
4. Mark anything not re-verified `unverified`; record the live probe used.

## Don't

- Don't hardcode magic numbers, model tags, ports, or timing values in code,
  comments, or skills when an SSOT config exists.
- Don't treat handoffs, blogs, or old SKILL.md sentences as constants — a stale
  spec is never evidence that "it was always like this".
- Don't relax the hard constants: Project Root rules, secret non-disclosure,
  openssl/opnessl key name/value/format/structure, foreign-lease preservation,
  latest-user-text precedence.

## Pair with

- `$demo1-api-spec-drift-guard`
- `$demo1-api-routing-inventory`
- `$demo1-core-request-router`
- `$demo1-work-ledger` (file changes still need journal + checkpoint)
