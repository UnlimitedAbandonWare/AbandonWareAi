---
name: demo1-session449-web-context-injection-assist-20261007
description: Read-only pin, coverage, lease overlap, stage classify, and diff checks for the Codex session449 web-context injection brief. Product source stays with Codex.
---

# session449 web-context injection assist (2026-10-07)

## When
Codex is patching the session449 web-context injection brief, and the assist side needs anchors, the one-stage rule, or the product-before-RED gate.

## SSOT
`var/codex-assist-session449-web-context-20261007/README.md`

## Check
```
python -B scripts/session449_web_context_injection_assist.py pin --root .
python -B scripts/session449_web_context_injection_assist.py cover --root .
python -B scripts/session449_web_context_injection_assist.py scope --root .
python -B scripts/session449_web_context_injection_assist.py next --root .
python -B scripts/session449_web_context_injection_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-session449-web-context-20261007/selftest_spec.py
```

## Do not
1. Edit product Java, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, or treat a synthetic GREEN as recovery of stored session 449.
3. Force-release `codex-context-export`, `codex-timeout-split-ae8f6e83`, or `madasin-recovery-continue-d75f6b20`.
4. Restore a SHA12 after pin reports DRIFT. Re-read the file.
5. Turn a null web list, a Sources URL, or `prompt.ctx.prefix.sha1` into proof that the body reached the model.
