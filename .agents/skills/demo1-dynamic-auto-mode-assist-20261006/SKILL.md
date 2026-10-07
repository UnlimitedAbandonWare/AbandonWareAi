---
name: demo1-dynamic-auto-mode-assist-20261006
description: Read-only pin, coverage, diff, and scorecard checks for the Codex Dynamic Auto brief. Product source stays with Codex.
---

# Dynamic Auto assist (2026-10-06)

## When
Codex is patching `DYNAMIC-AUTO-MODE-20261006-v1`, and the assist side needs anchors, forbid lines, the 10-turn counter card, or the fastpath counterexamples.

## SSOT
`var/codex-assist-dynamic-auto-mode-20261006/README.md`

## Check
```
python -B scripts/dynamic_auto_mode_assist.py pin --root .
python -B scripts/dynamic_auto_mode_assist.py cover --root .
python -B scripts/dynamic_auto_mode_assist.py diff-forbid --root . --diff <owned.diff>
python -B var/codex-assist-dynamic-auto-mode-20261006/selftest_spec.py
```

## Do not
1. Edit `ChatWorkflow.java`, `NoEvidenceChatFallback.java`, `ChatApiController.java`, `chat.js`, `chat-ui.html`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a Luna binding proof, or a browser run.
3. Force-release lease `chat-browser10-followup` or Codex lease `dynamic-auto-20261006-01a10ff8`.
4. Restore a paste SHA12 over a file Codex has already changed. Re-read it.
5. Add a router, a `DYNAMIC_AUTO` enum, a Luna alias, or a Gemini-to-Luna prompt injection.
