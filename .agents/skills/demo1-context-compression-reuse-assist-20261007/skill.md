---
name: demo1-context-compression-reuse-assist-20261007
description: Read-only pin, coverage, lease and journal overlap, Gemini defer, and diff checks for the Codex context-compression reuse brief. Product source stays with Codex.
---

# Context compression reuse assist (2026-10-07)

## When
Codex is patching the context-compression reuse brief, and the assist side needs anchors, the fixture-phrase list, or the Gemini defer gate.

## SSOT
`var/codex-assist-context-compression-reuse-20261007/readme.md`

## Check
```
python -B scripts/context_compression_reuse_assist.py pin --root .
python -B scripts/context_compression_reuse_assist.py cover --root .
python -B scripts/context_compression_reuse_assist.py scope --root .
python -B scripts/context_compression_reuse_assist.py defer --root .
python -B scripts/context_compression_reuse_assist.py next --root .
python -B scripts/context_compression_reuse_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-context-compression-reuse-20261007/selftest_spec.py
```

## Do not
1. Edit product Java, product resources, chat.js, or product tests from this skill.
2. Treat exit 0 as a product PASS, or treat a shorter summary as RED.
3. Start the Gemini summary work package while defer prints DEFER.
4. Force-release a live lease, or close journal display-gemini-search-b55c29ed or codex-timeout-split-ae8f6e83.
5. Restore a SHA12 after pin reports ANCHOR_STALE. Re-read the file.
6. Explain the session413 rejection as missing compression.
