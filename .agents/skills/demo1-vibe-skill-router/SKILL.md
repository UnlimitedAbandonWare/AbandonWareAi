---
name: demo1-vibe-skill-router
description: Use when a demo-1 ask needs routing to exactly one primary skill (+<=1 optional) via .agents/skills-intent-index.yaml; default entry for demo-1 vibe asks
---

# demo1-vibe-skill-router

Run once at task start, before picking skills:

```powershell
python -B scripts/demo1_vibe_skill_router.py resolve "<user text>"
python -B scripts/demo1_vibe_skill_router.py resolve --text-file ask.txt   # "-": stdin (PS 5.1-safe for Korean)
```

## Steps

1. Resolve once: run `resolve` with the user's exact wording (use `--text-file` when the ask has Korean/non-ASCII — PS 5.1 argv can be codepage-mangled).
2. Read the JSON: `intent`, `primary`, `optional`, `forbidden_skipped`, `redirects`, `notes`.
3. Invoke `primary`; add `optional` only when the ask truly needs it — never @-list skills (5+ mentions in one directive is a routing failure).
4. `intent: null` + `via: fallback` = nothing matched or soft-hit; `primary` still carries the index's default fallback skill (`demo1-vibe-max-agency`) — keep it or work directly, per ask shape. `primary: null` survives only when every resolved skill was hard-vetoed.
5. `status: error` (`skill-folder-missing`) = fix the index or folder; never substitute a near-match from `suggestions` silently.

## Verify

- Golden regression: `python -B scripts/tests/test_vibe_router_golden.py` — 52-case ask→primary set; pass rate must stay ≥90%. A pure-noise ask falls back to `demo1-vibe-max-agency` (`via: fallback`), not null.
- After editing `skills-intent-index.yaml`, re-run the golden suite before reporting.
- `--list-intents` dumps the effective intent/family table for audit diffs.

## Output

One JSON object per resolve: `{schemaVersion, intent, primary, optional, forbidden_skipped, unlocked_families, score, soft, softScore, vetoed, veto_relaxed, redirects, notes}` (fallback adds `via: fallback`); on index/usage error `{status: "error", reason}` with exit 2 (exit 0 on success, including the no-match fallback).

## Rules

- One resolve per goal: `resolve` runs once at task start. After a **goal
  switch** (objective rotated/rerouted, barrier `switch` executed), resolve the
  new ask again — `demo1_goal_switch_barrier.py switch --new-purpose` already
  echoes the fresh resolve; never reuse the old goal's `primary`.
- SSOT is `.agents/skills-intent-index.yaml`. Add or fix intents/families there,
  not here and not in ad-hoc prompt lists.
- The resolver follows `redirect:` on deprecated alias SKILL.md files (reported
  in `redirects`) and exits 2 with `suggestions` if a resolved skill folder is
  missing — fix the index or folder; never substitute a near-match silently.
- `--list-intents` prints the intent/family table for audit; `explicit: true`
  intents (PatchDrop/SMB/MacSrc, triad 심의, counter-evidence) outrank generic
  intents whenever the user's own words score them.
- Ties keep file order: put the more specific intent first.
- `forbid_families` (`counter-evidence`, `macsrc-patchdrop`, `triad`) stay off
  the default path; only the user's own wording unlocks them (PatchDrop/SMB/
  MacSrc, 심의/triad, 반증/counter-evidence). `macsrc-patchdrop` is `veto: hard`
  (skill dropped); `counter-evidence`/`triad` are `veto: soft` — the skill stays
  routed and is reported in `veto_relaxed` as a warning instead of being dropped.
- Per-intent `soft:` cues score only when every `match` misses (`soft: true`,
  `softScore` in output); they rescue paraphrase/typo asks without reordering
  precision hits.
- `demo1-core-request-router` remains the router's delegation target for
  classified Display/RAG/LLM phases — it is not a parallel entry point.
- No match and no soft cue resolves to the `fallback:` block — currently
  `demo1-vibe-max-agency` with `via: fallback`. It is a soft handoff, not a
  forced skill: if the ask is clearly out of scope, work directly.
- Resolved primary looks wrong for the ask? Second opinion:
  `python -B scripts/demo1_tool_placement_scan.py scan "<ask>"` ranks existing
  calls and flags known misroutes (`misroutes[]`); advisory only — it never
  replaces this router (AGENTS.md DEMO1-TOOL-PLACEMENT-SCAN).
- Routing grants no write authority: file-changing work still follows AGENTS.md
  work-ledger (journal → checkpoint preimage → verify), and application-source
  edits keep the existing lease gate.

## Hard stops

- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
