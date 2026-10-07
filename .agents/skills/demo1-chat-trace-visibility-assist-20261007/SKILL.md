---
name: demo1-chat-trace-visibility-assist-20261007
description: Read-only pin, coverage, diff-review, and lease checks for the Codex /chat per-answer trace default-visibility brief (CHAT_TRACE_DEFAULT_VISIBILITY_20261007). Product source stays with Codex.
---

# Chat trace default visibility assist (2026-10-07)

## When
Codex is patching `PASTE_CODEX_CHAT_TRACE_DEFAULT_VISIBILITY_20261007`
(resumed task `chat-trace-default-20261007-4574d8be`) and the assist side
needs anchor freshness, acceptance coverage, or in-flight diff review.

## SSOT
`var/codex-assist-chat-trace-visibility-20261007/README.md`

## Check
```
python -B scripts/chat_trace_visibility_assist.py hashes --root .
python -B scripts/chat_trace_visibility_assist.py pins --root .
python -B scripts/chat_trace_visibility_assist.py cover --root .
python -B scripts/chat_trace_visibility_assist.py diff-review --root .
python -B scripts/chat_trace_visibility_assist.py lease --root .
python -B var/codex-assist-chat-trace-visibility-20261007/selftest_spec.py
```

## Do not
1. Edit product Java/JS/HTML or product tests from this skill.
2. Treat OBSERVED_IN_DIFF or exit 0 as a product PASS — session tests and the
   fresh-browser matrix are the verdict path.
3. Reclaim or force-release `context-export`, `session449-*`, or the owning
   `chat-trace-default-*` leases; `ownerProcessId` 0 is not a dead owner.
4. Revert `googleSearchRescue*` or `ChatConversationExport` hunks inside
   chat.js/chat-ui.html — they belong to other sessions sharing this tree.
5. Write an old SHA-256 back after `hashes` reports DRIFTED — re-read the file.
