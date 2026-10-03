<!-- moved-from: AGENTS.md L285-L288 sha256=fabdc9996beada274d61372c75d33b8b3d4afe8c504a51756d06ac369e7be565 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-VIBE-SKILL-ROUTER -->
- Every vibe/daily ask starts by resolving ONE primary skill: `python -B scripts/demo1_vibe_skill_router.py resolve "<user text>"` (`$demo1-vibe-skill-router`); SSOT is `.agents/skills-intent-index.yaml` — add/fix intents there, not in ad-hoc prompt lists. Listing 5+ `@skill` mentions in one directive is a routing failure, not thoroughness.
- `forbid_families` (`counter-evidence`, `macsrc-patchdrop`, `triad`) stay off the default path unless the user's own wording names them. `demo1-core-request-router` is a delegation target for classified phases, not the vibe default scatter. No match (`intent: null`) = proceed without a skill; never force one.
<!-- END DEMO1-VIBE-SKILL-ROUTER -->
