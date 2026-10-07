---
name: demo1-session413-evidence-answer-assist-20261007
description: Read-only pin, coverage, lease overlap, proof-gap, and diff checks for the Codex session413 evidence and answer-recovery brief. Product source stays with Codex.
---

# session413 evidence and answer-recovery assist (2026-10-07)

## When
Codex is patching the session413 evidence and answer-recovery brief, and the assist side needs anchors, the one-hypothesis rule, or the product-before-RED gate.

## SSOT
`var/codex-assist-session413-evidence-answer-20261007/README.md`

## Check
```
python -B scripts/session413_evidence_answer_recovery_assist.py pin --root .
python -B scripts/session413_evidence_answer_recovery_assist.py cover --root .
python -B scripts/session413_evidence_answer_recovery_assist.py scope --root .
python -B scripts/session413_evidence_answer_recovery_assist.py next --root .
python -B scripts/session413_evidence_answer_recovery_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-session413-evidence-answer-20261007/selftest_spec.py
```

## Do not
1. Edit product Java, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, or treat a synthetic GREEN as recovery of the stored session.
3. Force-release owner `codex-session413-session413-recovery-910ee375`.
4. Restore a SHA12 after pin reports DRIFT. Re-read the file.
5. Widen the durable projection allowlist, or swap `shouldStop` for the raw draft, to clear a hold line.
