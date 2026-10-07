---
name: demo1-session-context-export-json-zip-assist-20261007
description: Read-only pin, coverage, lease overlap, hypothesis, and diff checks for the Codex /chat conversation JSON and ZIP export brief. Product source stays with Codex.
---

# Session context export JSON ZIP assist (2026-10-07)

## When
Codex is patching `PASTE_CODEX_SESSION_CONTEXT_EXPORT_JSON_ZIP_20261007`, and the assist side needs anchors, the one-hypothesis rule, or the product-before-RED gate.

## SSOT
`var/codex-assist-session-context-export-20261007/README.md`

## Check
```
python -B scripts/session_context_export_json_zip_assist.py pin --root .
python -B scripts/session_context_export_json_zip_assist.py cover --root .
python -B scripts/session_context_export_json_zip_assist.py scope --root .
python -B scripts/session_context_export_json_zip_assist.py next --root .
python -B scripts/session_context_export_json_zip_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-session-context-export-20261007/selftest_spec.py
```

## Do not
1. Edit product Java, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, or treat ALL_PRESENT as a finished download.
3. Reclaim owner `codex-context-export` or lease topic `context-export`. `ownerProcessId` 0 is not a dead owner.
4. Write an old SHA12 back after pin reports DRIFT. Re-read the file.
5. Raise the 262144-byte per-answer bundle cap, or add a browser archive library.
