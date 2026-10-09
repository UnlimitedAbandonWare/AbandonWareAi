---
name: demo1-auto-search-budget-assist-20261008
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, verify-plan checks plus an offline projected-budget simulator for the Codex brief AUTO-SEARCH-PREFLIGHT-BUDGET-20261008 (auto /chat search rejected at admission by projected budget guard; fast/LIGHT path preserved). Product source stays with Codex.
---

# Auto-search pre-flight budget assist (2026-10-08)

## When

Codex is patching AUTO-SEARCH-PREFLIGHT-BUDGET-20261008 and the assist side
needs the anchor pins, named-test coverage, lease-overlap scope check, WP1
hypothesis discipline, the HOLD ledger, a diff reviewer, the ordered
acceptance command list, or an offline simulation of projected retrieval/
provider work to compare AUTO vs LIGHT without running Java or the server.

## SSOT

`var/codex-assist-auto-search-budget-20261008/README.md` (live-tree anchor
audit, projection formula facts incl. the hangul 12x attempt multiplier,
lease picture at spec write)

## Check

```
python -B scripts/auto_search_budget_assist_20261008.py pin --root .
python -B scripts/auto_search_budget_assist_20261008.py cover --root .
python -B scripts/auto_search_budget_assist_20261008.py scope --root .
python -B scripts/auto_search_budget_assist_20261008.py hypothesis --root .
python -B scripts/auto_search_budget_assist_20261008.py hold --root .
python -B scripts/auto_search_budget_assist_20261008.py verify-plan --root .
python -B scripts/auto_search_budget_assist_20261008.py diff-forbid --root . --diff <owned.diff>
python -B scripts/auto_search_budget_assist_20261008.py product-gate --root . --diff <owned.diff>
python -B scripts/auto_search_budget_assist_20261008.py project --root . --message "<q>" --search-mode AUTO
python -B var/codex-assist-auto-search-budget-20261008/selftest_spec.py
```

`project` mirrors `PublicRequestBudgetGuard.validateChat` (projected path),
`projectedChatQueryCount`, `SearchPolicyEngine.decide`/`tuneTopK` and reports
queryMultiplier/workflowQueries/extremeZQueries/works/caps plus a verdict:
exit 0 ADMIT, 5 `chat_retrieval_budget_exceeded`, 6
`chat_provider_budget_exceeded`, 3 invalid input. Simulation only — an ADMIT
does not prove live admission (stale served build / saved settings can still
reject; that is hold item `served-build-version-unknown`).

`product-gate` maps added-line paths onto the brief's seam (PublicRequest-
BudgetGuard, ChatApiController, SearchPolicyEngine/Decision,
SelfAskSearchBudget, chat.js): IN_SCOPE/TEST_ONLY/ASSIST_ONLY pass,
SCOPE_EXPAND needs a journal record, PROTECTED_HIT/SECRET_LITERAL block.
`diff-forbid` flags FORCE_LIGHT injection, limit inflation, guard skip,
strictModelSelection:false, unbounded retry, secrets, and foreign-leased
naver-seam paths.

## Do not

1. Edit product Java, `application.yml`, chat.js, or product tests from this
   skill — they stay with the Codex session. chat.js already carries a
   foreign +17/-8 hunk; Codex edits there must preserve it.
2. Treat exit 0 / a simulated ADMIT as a product PASS or a live /chat verdict.
3. Force-release a lease reported by `scope` as OVERLAP; wait or coordinate.
4. Run the verify-plan Gradle commands from the assist session before the
   main agent's final report — it only lists them. `ChatPlanBudgetFocusedTest`
   lives in the `chatUiTest` sourceSet (`src/chatUiTest/java`), not `test`.
5. Bump `max-retrieval-work`/`max-provider-work`, disable the guard, or
   inject FORCE_LIGHT as a "fix" — the brief forbids all three.
6. Restart the shared 18180 server or send real searches — both are HOLD
   items owned by the Codex session's approval flow.
