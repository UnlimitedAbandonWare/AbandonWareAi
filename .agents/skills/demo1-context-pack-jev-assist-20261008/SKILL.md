---
name: demo1-context-pack-jev-assist-20261008
description: Read-only pin, coverage, lease-overlap, forbidden-list guard, HOLD ledger, env-presence, verify-plan, synthetic JEV failure mock, pack-lint contract check, token-estimate honesty check, and selftest for the Codex brief CODEX-CONTEXT-PACK-JEV-20261008 (local context packer inside gptpro_pack_context.py, optional JEV rerank, pinned contract). Product/packer implementation files stay with Codex.
---

# Codex context-pack + optional-JEV assist (2026-10-08)

## When

Codex is implementing `CODEX-CONTEXT-PACK-JEV-20261008` (ledger
`codex-context-pack-a71f89d1`, owns 3 files:
`scripts/gptpro_pack_context.py`, `scripts/test_gptpro_context_selection.py`,
`docs/agent-tooling/context-selection.md`) and the assist side needs anchor
pins, named coverage on the new test/doc, lease/journal overlap, the brief's
forbidden-list gate on diffs, the WP0/WP4/WP5 HOLD ledger, a synthetic
/v1/evaluate failure mock for T8, an advisory pack-format lint, a
len(text)//4 honesty check, or the ordered T1-T11 acceptance map.

## SSOT

`var/codex-assist-context-pack-jev-20261008/README.md` (verified live-tree
facts, Codex-owned file list, command card, status vocabulary).

## Check

```
python -B scripts/context_pack_jev_assist_20261008.py pin --root .
python -B scripts/context_pack_jev_assist_20261008.py cover --root .
python -B scripts/context_pack_jev_assist_20261008.py scope --root .
python -B scripts/context_pack_jev_assist_20261008.py guard --diff <d>
python -B scripts/context_pack_jev_assist_20261008.py diff-forbid --root . --diff <d>
python -B scripts/context_pack_jev_assist_20261008.py hold --root .
python -B scripts/context_pack_jev_assist_20261008.py verify-plan --root .
python -B scripts/context_pack_jev_assist_20261008.py env-presence --root .
python -B scripts/context_pack_jev_assist_20261008.py jev-mock --mode ok|empty_choices|bad_schema|auth_401|forbidden_403|rate_429|timeout_sim
python -B scripts/context_pack_jev_assist_20261008.py pack-lint --file <pack.json>
python -B scripts/context_pack_jev_assist_20261008.py token-estimate --file <doc>
python -B scripts/context_pack_jev_assist_20261008.py selftest --root .
```

`guard` enforces the brief's hard "do not" list on added diff lines: no
secret literals, no edits to immutable anchors (context_compression_reuse
_assist.py, jev_campaign_score.py, apikit/providers/jev.py,
jev_api_smoke.py, jev_gateway_smoke.mjs, checkpoint_doctor.py,
JevGatewayClient.java, demo1-session-state-checkpoint SKILL.md), no product
file or routing-config writes, no original-source deletion calls outside
tests, no git push/commit/add -A/--no-verify, no scheduler registration, no
hook config writes (PreCompact/SessionStart/additionalContext into
.codex/config.toml/hooks paths), no JEV-score-as-truth wording, no new
network calls in the packer (JEV reuses the existing client only after the
WP4 contract hold resolves), no wholesale `build_all()` calls outside the
packer file itself, AGENTS.md additions <=5 lines carrying a
context/selection scope word.

`pack-lint` is an advisory contract on a generated pack: pinned contract
block + per-item path/(lines|excerpt)/hash/reason + exclusion reasons +
budget block. Field names are loose - a MISS says "check the field", not
"the packer is wrong".

`token-estimate` puts len(text)//4 next to Korean-aware estimates; //4 is
never a tokenizer and always underestimates Korean/code.

`jev-mock` emits envelopes marked `synthetic`+`assumedSchema`; the real JEV
document-selection contract is UNVERIFIED, so its `ok` shape is a
placeholder for failure-path tests, not a verified response.

## Do not

1. Edit the 3 Codex-owned files, the immutable anchors, product code, or
   existing skills from this lane - they stay with the Codex session.
2. Treat exit 0, a PACK_OK, or a mock envelope as proof the packer is
   faster/more accurate or that JEV wiring works. T10 needs the real
   same-task comparison; token reduction alone is not PASS.
3. Call JEV, install hooks, register schedulers, read `.env`/secret values,
   or write outside the assist pack - env-presence emits booleans only.
4. Force-release a lease reported by `scope` as OVERLAP; OVERLAP on the 3
   OWNED paths is the Codex session itself, not a conflict.
5. Open HOLD items in `hold.json` as RESOLVED without real evidence
   (JEV host/schema/cost/approval, install판 hook support, BH meaning).
6. Send original sources or full transcripts externally; the brief caps
   JEV input at approved candidate IDs + short excerpts + metadata.
7. Ship prod/public writes: no commit/push, server restart, or hook
   auto-install - those stay user-gated.
