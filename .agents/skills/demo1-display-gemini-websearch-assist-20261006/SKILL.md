---
name: demo1-display-gemini-websearch-assist-20261006
description: Read-only pin, coverage, and diff checks for the Codex Display Gemini websearch-only brief. Product source stays with Codex.
---

# Display Gemini websearch-only assist (2026-10-06)

## When
Codex is patching `DEMO1-DISPLAY-GEMINI-WEBSEARCH-ONLY-20261006`, and the assist side needs anchors, forbid lines, or a named-test phrase scan.

## SSOT
`var/codex-assist-display-gemini-websearch-20261006/README.md`

## Check
```
python -B scripts/display_gemini_websearch_assist.py pin --root .
python -B scripts/display_gemini_websearch_assist.py cover --root .
python -B scripts/display_gemini_websearch_assist.py diff-forbid --root . --diff <owned.diff>
```

## Do not
1. Edit `main/java`, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a live Gemini call, or glasses proof.
3. Force-release live lease `display-gemini-only-79b3e3e9`. Overlap on one file holds that file only.
4. Add a second mode enum, store the mode in `webSearchEnabled`, or force search with `functionCallingConfig` ANY.
