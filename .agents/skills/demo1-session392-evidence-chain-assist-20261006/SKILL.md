---
name: demo1-session392-evidence-chain-assist-20261006
description: Read-only pin, coverage, lease overlap, and diff checks for the Codex session392 evidence-chain brief. Product source stays with Codex.
---

# session392 evidence-chain assist (2026-10-06)

## When
Codex is patching the session392 current-turn evidence identity brief, and the assist side needs anchors, the one-hypothesis rule, or the product-before-RED gate.

## SSOT
`var/codex-assist-session392-evidence-chain-20261006/README.md`

## Check
```
python -B scripts/session392_evidence_chain_assist.py pin --root .
python -B scripts/session392_evidence_chain_assist.py cover --root .
python -B scripts/session392_evidence_chain_assist.py scope --root .
python -B scripts/session392_evidence_chain_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-session392-evidence-chain-20261006/selftest_spec.py
```

## Do not
1. Edit product Java, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS or as proof that the two live turns bound.
3. Force-release owner `codex-dynamic-auto-dynamic-auto-social-resume-06-863d481f`.
4. Restore a SHA12 after pin reports DRIFT. Re-read the file.
5. Widen the durable projection allowlist, or fill the evidence card, to make the omission control pass.
