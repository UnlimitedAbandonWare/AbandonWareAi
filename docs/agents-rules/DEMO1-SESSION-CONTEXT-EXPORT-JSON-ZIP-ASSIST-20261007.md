# Session context export JSON ZIP assist (2026-10-07)

Pointer only. Codex owns the product patch. The live owner journal is `context-export-8c75705b` for `codex-context-export`.

SSOT: `var/codex-assist-session-context-export-20261007/README.md`

```
python -B scripts/session_context_export_json_zip_assist.py pin --root .
python -B scripts/session_context_export_json_zip_assist.py cover --root .
python -B scripts/session_context_export_json_zip_assist.py scope --root .
python -B scripts/session_context_export_json_zip_assist.py product-gate --root . --diff <owned.diff>
```

A scan exit 0 is not a product PASS. Do not edit product source from this rail. Do not reclaim the context-export lease. Do not edit `docs/PROJECT_STATUS.md` while another journal names it. Do not write a drifted SHA12 back.
