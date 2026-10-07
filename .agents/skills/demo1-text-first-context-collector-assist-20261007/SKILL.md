---
name: demo1-text-first-context-collector-assist-20261007
description: Read-only pin, hash, coverage, state, diff-review, lease, and HOLD-ledger checks for the Codex brief TEXT-FIRST-CONTEXT-COLLECTOR-20261007-v1 (same-owner/session context preservation + conditional one-shot Gemini Lite selector review). Product source stays with Codex.
---

# Text-first context collector assist (2026-10-07)

## When
Codex is patching `PASTE_CODEX_TEXT_FIRST_CONTEXT_COLLECTOR_20261007`
(contract `TEXT-FIRST-CONTEXT-COLLECTOR-20261007-v1`; owning lease topic
`text-first-context-collector-b6a14853` at assist-build time) and the assist
side needs anchor freshness, brief-hash drift, A1-A10 coverage cues,
forbidden-line diff cues, lease overlap, the HOLD ledger, or a journal timing
gate.

## SSOT
`var/codex-assist-text-first-context-collector-20261007/README.md`

## Check
```
python -B scripts/text_first_context_collector_assist.py state --root .
python -B scripts/text_first_context_collector_assist.py hashes --root .
python -B scripts/text_first_context_collector_assist.py pins --root .
python -B scripts/text_first_context_collector_assist.py cover --root .
python -B scripts/text_first_context_collector_assist.py diff-review --root . [--diff <owned.diff>]
python -B scripts/text_first_context_collector_assist.py lease --root .
python -B scripts/text_first_context_collector_assist.py holds --root .
python -B scripts/text_first_context_collector_assist.py snapshot --root .
python -B scripts/text_first_context_collector_assist.py journals --root .
python -B var/codex-assist-text-first-context-collector-20261007/selftest_spec.py
```

## Do not
1. Edit product Java/resources/tests from this skill — the product seam is
   leased/owned by the Codex `text-first-context-collector-*` session.
   `ChatHistoryServiceImpl.java` was also live-leased by
   `browser10-summary-boundary` at assist-build time: overlap there is a
   per-target BLOCKED_LEASE cue for the owner, never force-released here.
2. Touch `scripts/browser10_support_setup.py` / `test_browser10_support_setup.py`
   in any way — the contract bans read/write/run/reuse.
3. Treat OBSERVED/state/cover exit 0 as a product PASS, or a mock/recorded A/B
   as real Lite quality/latency/cost evidence — focused Gradle C1-C6 and a
   separately approved real run decide.
4. Enable Lite live calls, attach a search/grounding tool to the selector,
   pin a `GeminiLite` alias instead of an official model ID, or send private
   history to a remote provider — all contract HOLD/forbidden.
5. Write an old SHA12 back after `hashes` reports DRIFTED — re-read the file.
6. Add a resident server/daemon/orchestrator or a shadow PromptBuilder path.
