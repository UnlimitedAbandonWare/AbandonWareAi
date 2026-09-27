---
paths:
  - "**/assets/display/**"
  - "**/*meta-display*/**"
  - "**/*Conversate*"
  - "**/application-meta-display.yml"
  - "**/META_DISPLAY*"
  - "**/receiver.js"
---

# Meta Ray-Ban Display — load canonical skill first

This rule only activates for Display-path work (see `paths`).

- Before changing hint length/lines, lens lifecycle/TTL, paging, allowed lens fields, or soft-evidence gates: read `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (canonical display/output contract + FIELD_TESTED runtime policy).
- Hint generation/paging knobs live in `application-meta-display.yml`; Java/YAML changes reach live only via compile + ForceRestart/DevWatch — never claim success from a stale bootRun.
- Ambiguous Display/RAG/LLM tradeoffs: `.agents/skills/demo1-triad-deliberation/SKILL.md`.
