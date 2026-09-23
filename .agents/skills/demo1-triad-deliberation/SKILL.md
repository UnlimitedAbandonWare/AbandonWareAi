---
name: demo1-triad-deliberation
description: >-
  Use for non-trivial demo-1 decisions, ambiguous failures, API/spec drift,
  Ray-Ban Display/RAG/LLM tradeoffs, or when the user asks for
  positive/negative/neutral cross-check. Runs affirmative, adversarial, and
  calm judge passes with optional web fact-check, then returns a verdict-first
  answer. Not for trivial wording edits, token-save stops, single-seam patches
  that are already clear, or when the user forbids deliberation.
---

# Demo1 Triad Deliberation

For non-trivial judgments, run a short **affirmative (opportunity) -> adversarial (counterexample) -> calm judge** pass, then web/live-check only unknown facts. Same family as multi-agent debate / self-consistency. This is an **internal** reasoning pattern, not three paid model calls.

## When NOT to run (token / safety)

- Trivial wording, typos, or a patch whose seam is already obvious
- User asked to save tokens, self-verify, or stop -> `$demo1-agent-api-spend-guard`
- Another primary skill already owns the seam (`$demo1-conversate-hint-evidence`, `$demo1-meta-display-simple-caption`, etc.) -> that skill wins; triad is optional
- No paid multi-model fanout unless `AWX_AGENT_ALLOW_PAID_MODELS=1`. Prefer **1-2 internal role passes in one session**

## Priority

1. User explicit request
2. Domain / vendor standard (Meta Web App, live API, project SSOT)
3. This skill + AGENTS **hard** constraints
4. Heuristics

Never say the word "mode" to the user.

## Roles (internal only — do not narrate as three bots)

### Affirmative (exploratory)
- Workable hypotheses, opportunities, success paths
- Speculation only if **labeled**. No temperature-2 / random creative-leap theatrics. No irreversible action from this pass alone.

### Negative (adversarial)
- Failure paths, risks, missed counterexamples
- Prefer concrete breakage: black screen, TLS/HTTPS, quota, data loss, secret leak, irreversible migrate, sticky-link expiry, unowned restart

### Judge (calm, literal)
- No metaphors. Facts vs estimates only
- Mark overconfidence on either side
- Pick what survives counterexamples **and** matches live/code evidence
- **Agreement is not proof** (consistency illusion). Role consensus is not verification
- Cap: one cross-check round unless new evidence appears. Early-stop when the actionable seam is clear
- Unknowns -> bounded web search / live probe. No plugin-catalog spam in the user message

## Output shape

1. **Final judgment first** (no canned openers)
2. Short chronological core: situation -> reason -> ask/action
3. Brief affirmative / negative notes, light web cites, ranked residual causes
4. Soft rules may relax if total loss drops and core purpose holds; **hard / irreversible / safety** never relax
5. Body-risk (lens/eye): warn strictly. Unconventional hardware may remain a valid alternative with benefit+risk
6. If a photo cannot confirm a fixed state, ask for a closer shot instead of blocking forever

## Soft vs hard (examples)

| Soft (may relax) | Hard (never relax) |
|---|---|
| Extra docs, verbose logs, perfect naming | Printing secrets, deleting `.secrets`, force-push, killing unrelated processes |
| Skipping live soak when user self-verifies | Fake citations; disabling all Brave on free quota; mixing DAT+WebApp+custom relay in one change |

## Code / config safety

- Change **only needed slices**; no blanket overwrite
- Prefer env / `.secrets` / existing key loaders; never print secret values
- Empty-search progressive recovery belongs to search/RAG owners (`$demo1-api-spec-drift-guard`). Do **not** override Conversate hint-empty policy (`$demo1-conversate-hint-evidence`)
- Fact vs estimate; reversible next probes with expected effect, success metric, stop condition. Temporary improvement != root cause

## Pair with

- `$demo1-core-request-router`
- `$demo1-api-spec-drift-guard`
- `$demo1-meta-display-simple-caption`
- `$demo1-conversate-hint-evidence`
- `$demo1-agent-api-spend-guard`
- `$demo1-evidence-debugging`

## Related
- User alias Skill: `positive-negative-neutral-judge` (same deliberation contract for Devin Desktop).

