---
name: demo1-vibe-skill-router
description: Use at task start to pick exactly one primary skill (+<=1 optional) via .agents/skills-intent-index.yaml; default entry for demo-1 vibe asks
---

# demo1-vibe-skill-router

Run once at task start, before picking skills:

```powershell
python -B scripts/demo1_vibe_skill_router.py resolve "<user text>"
```

Follow the JSON: invoke `primary` (+ at most one `optional`, only when the ask
truly needs it). Never @-list skills — 5+ mentions in one directive is a routing
failure, not thoroughness.

## Rules

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
  MacSrc, 심의/triad, 반증/counter-evidence).
- `demo1-core-request-router` remains the router's delegation target for
  classified Display/RAG/LLM phases — it is not a parallel entry point.
- No match (`intent: null`) means proceed without a skill; never force one.
- Routing grants no write authority: file-changing work still follows AGENTS.md
  work-ledger (journal → checkpoint preimage → verify), and application-source
  edits keep the existing lease gate.

## Hard stops

- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
