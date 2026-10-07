# session413 evidence and answer-recovery assist (2026-10-07)

Pointer only. Codex owns the product patch. The live owner is `codex-session413-session413-recovery-910ee375`.

SSOT: `var/codex-assist-session413-evidence-answer-20261007/README.md`

```
python -B scripts/session413_evidence_answer_recovery_assist.py pin --root .
python -B scripts/session413_evidence_answer_recovery_assist.py cover --root .
python -B scripts/session413_evidence_answer_recovery_assist.py scope --root .
python -B scripts/session413_evidence_answer_recovery_assist.py product-gate --root . --diff <owned.diff>
```

A scan exit 0 is not a product PASS. Do not edit product source from this rail. Do not force-release the session413 leases. Do not edit `docs/PROJECT_STATUS.md` while the report lease holds it.
