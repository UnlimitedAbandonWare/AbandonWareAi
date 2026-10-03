<!-- moved-from: AGENTS.md L254-L258 sha256=bf0f327dd6f233321218c127c865e2e1c7c349b2957bc05a9336912da394cfe2 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-DEVIN-DIRECTIVE-LOOP -->
## External-Agent Directive Loops (Devin report ↔ follow-up directive)
- Devin work-report reply or source-fix directive drafting -> `$demo1-devin-directive-loop` (`.agents/skills/demo1-devin-directive-loop/SKILL.md`); classify DRAFT / REVIEW / CLOSE once per turn. REVIEW replies are **delta-only** — challenge only unproven evidence tiers or fresh contradictions; never re-audit verified items or widen scope.
- Close-out is durable: results land in `docs/PROJECT_STATUS.md` via `scripts/status_doc.py`; instruction changes land in AGENTS.md/skill BEGIN/END markers — no resurrecting pre-fix instructions (DEMO1-STALE-HANDOFF-REFERENCE). Pitfalls: the skill's `references/review-loop-patterns.md`.
<!-- END DEMO1-DEVIN-DIRECTIVE-LOOP -->
