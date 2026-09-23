---
name: frontend-display-debug
description: "Use when Fold6/web Meta display UI is broken: assets, receiver.js, lens, cache"
triggers:
  - user
  - model
redirect: demo1-meta-display-browser-repair
deprecated: true
---

# frontend-display-debug

## Source of truth (alias)
Read and follow `.agents/skills/demo1-meta-display-browser-repair/SKILL.md`. Edit the SSOT, not this alias.

Lens **timing and cue cycle** (전사/힌트 유지, `1/2→2/2` 간격, 20 s hold, ~2 s page floor, 2.5 s quiet, 10 s cooldown, 180 s force) is not a cache/UI-debug ticket. Implement on existing `#lens-display` so saved prefs persist and the live cycle changes. Do not stop at a Fold screenshot report. Do not treat YAML seconds as frozen.

## Background listen (Fold other-tab)

User report: capture starts, then after ~1–2 min in another page/app/lens tab it drops. Video may still reconnect while the capture UI is open — do not blame battery-only.

Trace mic → ASR → server → session → lens. Confirm the live engine; do not assume an unused WebSocket. `docs/meta-display-integration.md` notes `visibilitychange`/`pagehide` currently pause capture — treat that as a lead, re-read current `assets/display` (`display-voice.js`, `display-conversate.js`, `conversate/app.js`).

- Display-tab **view** must not steal Fold capture ownership.
- Hidden page ≠ user stop. Resume the same session on return; do not auto-start a user-stopped capture.
- Connection ≠ capturing. Expose last-audio and last-transcript times (already on `DisplayConversateController.Binding`).
- A keepalive tab is not proof of background mic. If the browser freezes timers, report that limit and keep the existing server/display; an Android `microphone` foreground-service helper is a **separate scope**, not this patch.
- After the user switches tabs or returns from the lens page, `python -B scripts/devin_task_orchestrate.py capture --role wear --invoke`. Success is last-audio **and** last-transcript timestamps advancing, not a live WebSocket or “수음 중” label.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
