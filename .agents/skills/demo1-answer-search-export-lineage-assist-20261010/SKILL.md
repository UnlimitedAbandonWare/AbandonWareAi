---
name: demo1-answer-search-export-lineage-assist-20261010
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, verify-plan, mojibake-scan, session-hash, and audit-levels checks for the Codex brief PASTE_CODEX_answer_search_export_lineage_20261010 (contract D1-ANSWER-SEARCH-EXPORT-LINEAGE-20261010-R1: WP1 free-idea header dedup, WP2 search decision/execution/evidence truth, WP3 export fence + canonical session binding, WP4 lineage observation levels). Product source stays with the patching session.
---

# Answer/Search/Export/Lineage assist (2026-10-10)

## When

A patching session applies contract `D1-ANSWER-SEARCH-EXPORT-LINEAGE-20261010-R1`
(Downloads `PASTE_CODEX_answer_search_export_lineage_20261010.txt`, four WPs on
main `/chat`: free-idea header duplication, search decision/execution/citable
truth, export capture scope + `chat-<id>` vs `<id>` ring binding, lineage audit
observation levels). This skill holds the anchor pins, the named-marker coverage
check, the lease-overlap scope check, the RED-before-product gate, and the
ordered acceptance command list.

## SSOT

`var/devin-assist-answer-search-export-lineage-20261010/README.md` (live-tree
facts vs brief claims, verified mojibake lines, hash math, lease picture at
pack build)

## Check

```
python -B scripts/answer_search_export_lineage_assist_20261010.py pin --root .
python -B scripts/answer_search_export_lineage_assist_20261010.py cover --root .
python -B scripts/answer_search_export_lineage_assist_20261010.py scope --root .
python -B scripts/answer_search_export_lineage_assist_20261010.py hypothesis --root .
python -B scripts/answer_search_export_lineage_assist_20261010.py hold --root .
python -B scripts/answer_search_export_lineage_assist_20261010.py verify-plan --root . [--contract wp1-header|wp2-search-evidence|wp3-export-ring|wp4-lineage]
python -B scripts/answer_search_export_lineage_assist_20261010.py diff-forbid --root . --diff <owned.diff>
python -B scripts/answer_search_export_lineage_assist_20261010.py product-gate --root . --diff <owned.diff>
python -B scripts/answer_search_export_lineage_assist_20261010.py mojibake-scan --root . [--file main/java/com/example/lms/service/ChatWorkflow.java]
python -B scripts/answer_search_export_lineage_assist_20261010.py session-hash --sid 622 --sid chat-622
python -B scripts/answer_search_export_lineage_assist_20261010.py audit-levels --root . --log var/rag-launcher/<run>/chat-ui-vibe-listener-18180.out.log
python -B var/devin-assist-answer-search-export-lineage-20261010/selftest_spec.py
```

`product-gate` unlocks product diffs only behind `red-boundary.json`
(one boundary + 1-4 allowPaths inside PRODUCT; copy
`red-boundary.example.json` per WP cycle).

## Do not

1. Edit product Java, product resources, chat.js/chat-conversation-export.js,
   display/interview/meta assets, plan YAML, or gradle files from this skill —
   they are FOREIGN_SURFACE in product-gate and `guardrail-write` in diff-forbid.
2. Treat exit 0 as a product PASS, a live-search success proof, or a
   shipped-behavior verdict.
3. Force-release the active Codex `answer-search-export-lineage` lease or the
   `nova-focus-stay-alive` lease — OVERLAP means wait.
4. Replace the healthy ProjectionMergeService default header — the confirmed
   defect is the mojibake literals inside ChatWorkflow free-idea prompts only.
5. "Fix" the WP3 hash by stripping `chat-` prefixes or ignoring the mismatch —
   the contract wants one canonical binding/version, and `session-hash` exists
   to reproduce `622`≠`chat-622` offline.
6. Flip `CitationGate.logOnly` globally, add a noun→web global rule, persist
   OFF on key failure, fabricate citations, or delete provider clients — all
   are diff-forbid hits.
7. Count `responseObserved` as provider receipt proof in WP4 work — client
   observation must stay NOT_OBSERVED without a controlled receipt.
8. Run the verify-plan Gradle/live commands from this assist session before
   the patching session's final report — it only lists them.
