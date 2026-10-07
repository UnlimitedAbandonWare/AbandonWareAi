---
name: demo1-gemini-rescue-toggle-assist-20261006
description: Read-only pin, coverage, and diff checks for the Codex Gemini rescue toggle persistence brief. Product source stays with Codex.
---

# Gemini rescue toggle assist (2026-10-06)

## When
Codex is patching `DEMO1-GEMINI-RESCUE-TOGGLE-PERSISTENCE-20261006-V1`, and the assist side needs anchors, the omission-versus-false check, or forbid lines.

## SSOT
`var/codex-assist-gemini-rescue-toggle-20261006/README.md`

## Check
```
python -B scripts/gemini_rescue_toggle_assist.py pin --root .
python -B scripts/gemini_rescue_toggle_assist.py cover --root .
python -B scripts/gemini_rescue_toggle_assist.py diff-forbid --root . --diff <owned.diff>
python -B var/codex-assist-gemini-rescue-toggle-20261006/selftest_spec.py
```

## Do not
1. Edit product Java, product resources, `chat.js`, `chat-ui.html`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a browser run, or proof that Gemini search ran.
3. Force-release Codex lease `gemini-toggle-persistence-b1e5ee28` or `gemini-owner-visible`.
4. Restore a paste SHA12 after pin reports DRIFT. Re-read the file.
5. Turn an untouched checkbox into explicit OFF, or flip `mainPromptPermission` to true.