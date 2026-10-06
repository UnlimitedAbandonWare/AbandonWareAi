---
name: demo1-pair-brief-assist-20261005
description: Read-only pin, coverage, and diff checks for the Codex multiuser-resilience and session-data-consent briefs. Product source stays with Codex.
---

# Pair-brief assist (2026-10-05)

## When
Codex is patching `DEMO1-MULTIUSER-RESILIENCE-20261005` or `DEMO1-SESSION-DATA-CONSENT-20261005`, and the assist side needs anchors, forbid lines, or a named-test phrase scan.

## SSOT
`var/codex-assist-pair-brief-20261005/README.md`

## Check
```
python -B scripts/pair_brief_assist.py pin --root .
python -B scripts/pair_brief_assist.py cover --root .
python -B scripts/pair_brief_assist.py diff-forbid --root . --diff <owned.diff>
```

## Do not
1. Edit `main/java`, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a live multi-user result, or consent-collection completion.
3. Force-release a live source lease. Overlap on one file holds that work package only.
4. Use graph consent epoch or the trace recorder as the improvement-consent store.
