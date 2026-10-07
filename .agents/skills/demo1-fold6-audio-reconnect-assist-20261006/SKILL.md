---
name: demo1-fold6-audio-reconnect-assist-20261006
description: Read-only pin, coverage, lease overlap, entry bind, and route-card checks for the Codex Fold6 audio reconnect brief. Product source stays with Codex.
---

# Fold6 audio reconnect assist (2026-10-06)

## When

Codex is patching the remaining standalone Display audio symptom, and the assist side needs the closed-guard pin, the one-route card, or the product-before-RED gate.

## SSOT

`var/codex-assist-fold6-audio-reconnect-20261006/README.md`

## Check

```
python -B scripts/fold6_audio_reconnect_assist.py pin --root .
python -B scripts/fold6_audio_reconnect_assist.py cover --root .
python -B scripts/fold6_audio_reconnect_assist.py scope --root .
python -B scripts/fold6_audio_reconnect_assist.py entry --root . --card <entry.json>
python -B scripts/fold6_audio_reconnect_assist.py route --root . --card <route.json>
python -B scripts/fold6_audio_reconnect_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-fold6-audio-reconnect-20261006/selftest_spec.py
```

## Do not

1. Edit product Java, product resources, `chat.js`, `chat-ui.html`, or product tests from this skill.
2. Treat exit 0 as a product PASS or as proof the video symptom is gone.
3. Force-release owner `codex-fold6-reconnect-fold6-reconnect-stop-20261006-cacd59cf`.
4. Restore a SHA12 after pin reports DRIFT. Re-read the file.
5. Use `static/conversate/app.js` or main `/chat` as the evidence surface.
6. Raise the pinned timeouts, auto-start the microphone on pageshow, or resend dropped audio.
