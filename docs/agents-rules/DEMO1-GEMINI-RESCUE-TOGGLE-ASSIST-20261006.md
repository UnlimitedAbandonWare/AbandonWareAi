# Gemini rescue toggle assist (2026-10-06)

Pointer only. Codex owns the product patch for `DEMO1-GEMINI-RESCUE-TOGGLE-PERSISTENCE-20261006-V1`.

SSOT: `var/codex-assist-gemini-rescue-toggle-20261006/README.md`

```
python -B scripts/gemini_rescue_toggle_assist.py pin --root .
python -B scripts/gemini_rescue_toggle_assist.py cover --root .
python -B scripts/gemini_rescue_toggle_assist.py diff-forbid --root . --diff <owned.diff>
```

A scan exit 0 is not a product PASS. Do not edit `main/java`, product resources, `chat.js`, or product tests from this rail. Do not force-release journal `gemini-toggle-persistence-b1e5ee28`.