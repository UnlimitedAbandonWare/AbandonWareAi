---
name: demo1-category-cleanup-map
description: >-
  Use when a demo-1 agent is about to touch files that look like junk, dormant
  code, duplicate runtimes, stale agent surfaces, or ghost tooling — i.e.
  anything matching categories C1~C5 of DEMO1-DEVIN-CATEGORY-CLEANUP-TARGETS-20260928.
---

# demo1-category-cleanup-map

Use when a demo-1 agent is about to touch files that look like junk, dormant code,
duplicate runtimes, stale agent surfaces, or ghost tooling — i.e. anything matching
categories C1~C5 of `DEMO1-DEVIN-CATEGORY-CLEANUP-TARGETS-20260928`.

## SSOT

- `docs/diagnostics/category-cleanup-0928/00_CATEGORY_MAP.md` — category definitions + agent reading rules
- `01_JUNK_AND_NAME_TRAPS.md` — C1 revalidation table (12 rows)
- `02_DORMANT_INDEX.md` — C2 package/group index (52 groups, live vs wired vs test-pinned vs candidate)
- `03_RUNTIME_CONTRACT_CARD.md` — C3 launcher/config/plans card
- `04_AGENT_SURFACE_SPRAWL.md` — C4 skills/prompts/rules/diagnostics surface map
- `05_ACTION_QUEUE.md` — P0/P1/P2 queue; approval gates per row
- `scan-report.json` — machine evidence; regenerate read-only via
  `python -B scripts/category_cleanup_scan.py --root . --out docs/diagnostics/category-cleanup-0928/scan-report.json`

## Rules

- Verdict ≠ action. `DELETE_CANDIDATE`/`QUARANTINE_CANDIDATE` rows are proposals — apply only per `05_ACTION_QUEUE.md` gates (approval + lease).
- 3중 증명 전 삭제 금지: 스캔 밖 + 미import + 미호출.
- Test-pinned paths (`ZombiePurgeContractTest` 등) are KEEP regardless of name.
- Mass deletion, product refactors, and foreign-lease takeover stay forbidden.
