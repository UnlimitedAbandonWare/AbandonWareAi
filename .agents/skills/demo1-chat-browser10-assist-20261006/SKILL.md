---
name: demo1-chat-browser10-assist-20261006
description: Read-only pin, coverage, diff, and scorecard checks for the Codex main /chat 10-turn regression brief. Product source stays with Codex.
---

# Main /chat 10-turn regression assist (2026-10-06)

## When
Codex is patching the 2026-10-06 main `/chat` 10-turn regression, and the assist side needs anchors, the remembered-value phrases, a diff forbid check, or a redacted scorecard shape.

## SSOT
`var/codex-assist-chat-browser10-20261006/README.md`

## Check
```
python -B scripts/chat_browser10_assist.py pin --root .
python -B scripts/chat_browser10_assist.py cover --root .
python -B scripts/chat_browser10_assist.py diff-forbid --root . --diff <owned.diff>
python -B scripts/chat_browser10_assist.py score --root .
```

## Do not
1. Edit `main/java`, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a 10-turn PASS, an OAuth proof, or a browser run.
3. Force-release live lease `chat-browser10-memory`. Overlap on one file holds that file only.
4. Raise `maxRetrievalWork`, zero the call budget, turn Gemini rescue on by default, or add a history-memory route.
