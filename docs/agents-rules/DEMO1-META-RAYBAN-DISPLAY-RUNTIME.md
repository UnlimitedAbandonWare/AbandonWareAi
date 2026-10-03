<!-- moved-from: AGENTS.md L125-L129 sha256=e116aa747e26530503e37a640a58e415215c1a3bf681f0d543364f2639e37330 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
## Meta Ray-Ban Display runtime (short)
- Display/output contract + runtime policy (settings-driven hold/paging/cue-cycle/budgets): SSOT `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (`$demo1-meta-display-simple-caption`) + always-on layer `.windsurf/rules/meta-rayban-display-runtime.md`. **Live Fold `#lens-display` prefs win over `application-meta-display.yml` factory defaults** — settings-driven knobs persisted via `lensSettings`; never silently clamp; last-page interval shrink forbidden. Numbers: `docs/volatile-knobs.md` (re-read; stale skill numbers lose).
- Live Java/YAML change -> compile then ForceRestart/DevWatch (`$demo1-dev-reload`, DEMO1-SPRING-VIBE-RELOAD); never claim live success from a stale bootRun. Ambiguous Display/RAG/LLM tradeoffs: `$demo1-triad-deliberation` / `$positive-negative-neutral-judge`.
<!-- END DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
