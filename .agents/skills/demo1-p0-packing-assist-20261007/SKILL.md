---
name: demo1-p0-packing-assist-20261007
description: Read-only pin, coverage, state, diff-review, and lease checks for the Codex P0 duplicate-URL prompt-packing brief (PASTE_CODEX_DEMO_P0_NEXT_FIX_20261007). Product source stays with codex-p0-packing-*.
---

# P0 duplicate-URL prompt packing assist (2026-10-07)

## When
Codex is patching `PASTE_CODEX_DEMO_P0_NEXT_FIX_20261007` (owning session
`codex-p0-packing-*`, lease on StandardPromptBuilder.java +
StandardPromptBuilderEvidenceMetadataTest.java + ChatWorkflowPromptMessageRoleTest.java)
and the assist side needs anchor freshness, RED staging state, acceptance
coverage, or in-flight diff review.

## SSOT
`var/codex-assist-p0-packing-20261007/README.md`

## Check
```
python -B scripts/p0_packing_assist.py state --root .
python -B scripts/p0_packing_assist.py hashes --root .
python -B scripts/p0_packing_assist.py pins --root .
python -B scripts/p0_packing_assist.py cover --root .
python -B scripts/p0_packing_assist.py diff-review --root .
python -B scripts/p0_packing_assist.py lease --root .
python -B var/codex-assist-p0-packing-20261007/selftest_spec.py
```

## Do not
1. Edit product Java or product tests from this skill — the 3 targets are
   leased by the owning Codex session; the 4 no-touch files (WebSearchRetriever,
   PageContentScraper, ChatWorkflow, SearchDecisionService) stay frozen.
2. Treat state/cover OBSERVED or exit 0 as a product PASS — the owning
   session's focused Gradle run (3 test classes) is the verdict path.
3. Reclaim or force-release the `codex-p0-packing-*` lease;
   `ownerProcessId` 0 / absent heartbeat is not a dead owner.
4. Run Gradle while the owning session is mid-work — shared build lane;
   re-run `state` after its final report instead.
5. Write an old SHA-256 back after `hashes` reports DRIFTED — re-read the file.
