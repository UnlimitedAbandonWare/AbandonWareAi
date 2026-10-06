---
name: demo1-display-search-capability-assist-20261006
description: Read-only pin, coverage, gap, and diff checks for the Codex Display search capability fallback brief. Product source stays with Codex.
---

# Display search capability fallback assist (2026-10-06)

## When
Codex is patching `DEMO1-DISPLAY-SEARCH-CAPABILITY-FALLBACK-20261006`, and the assist side needs anchors, the effective-fallback marker, the timeout-class gap, or a diff forbid check.

## SSOT
`var/codex-assist-display-search-capability-20261006/README.md`

## Check
```
python -B scripts/display_search_capability_assist.py pin --root .
python -B scripts/display_search_capability_assist.py cover --root .
python -B scripts/display_search_capability_assist.py gap --root .
python -B scripts/display_search_capability_assist.py diff-forbid --root . --diff <owned.diff>
```

## Do not
1. Edit `main/java`, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a live Gemini call, or glasses proof.
3. Force-release live lease `display-capability-01a10ef1`. Overlap on one file holds that file only.
4. Add a fourth `Routing` constructor field, a second mode enum, or `functionCallingConfig` ANY. Keep `effectiveFallbackAllowed()` as the READ_ONLY getter.
