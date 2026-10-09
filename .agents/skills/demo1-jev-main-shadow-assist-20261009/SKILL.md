---
name: demo1-jev-main-shadow-assist-20261009
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, and verify-plan checks for the FOR_CODEX brief in devin-jev-llm-decision-combo-8d094e46 (floor -> JevDecisionAdvisor shadow hook on the main /chat AUTO branch, then release-hedge on verification_rejected/insufficient). Product source stays with the patching session.
---

# Jev main-shadow + release-hedge assist (2026-10-09)

## When

A patching session (the "ambitious-request" Devin session) applies
`data/agent-handoff/devin-jev-llm-decision-combo-8d094e46/FOR_CODEX.md`:
JevDecisionAdvisor.advise("main", ...) wired into `shouldUseWebForSearchMode`'s
AUTO branch (shadow first) plus a hedged-release policy on
verification_rejected/insufficient. This skill holds the anchor pins, the
named-marker coverage check, the lease-overlap scope check, the RED-before-product
gate, and the ordered acceptance command list.

## SSOT

`var/devin-assist-jev-main-shadow-20261009/README.md` (live-tree facts vs brief
claims, drift table, the two wiring corrections, lease picture at spec write)

## Check

```
python -B scripts/jev_main_shadow_assist_20261009.py pin --root .
python -B scripts/jev_main_shadow_assist_20261009.py cover --root .
python -B scripts/jev_main_shadow_assist_20261009.py scope --root .
python -B scripts/jev_main_shadow_assist_20261009.py hypothesis --root .
python -B scripts/jev_main_shadow_assist_20261009.py hold --root .
python -B scripts/jev_main_shadow_assist_20261009.py verify-plan --root . [--contract jev-main-shadow|release-hedge]
python -B scripts/jev_main_shadow_assist_20261009.py diff-forbid --root . --diff <owned.diff>
python -B scripts/jev_main_shadow_assist_20261009.py product-gate --root . --diff <owned.diff>
python -B var/devin-assist-jev-main-shadow-20261009/selftest_spec.py
```

`product-gate` unlocks product diffs only behind `red-boundary.json`
(boundary + 1-4 allowPaths inside PRODUCT; copy `red-boundary.example.json`).

## Do not

1. Edit product Java, product resources, chat.js/chat-ui.html, display/interview/meta assets, or the existing Jev machinery (JevDecisionAdvisor / JevEvaluationRuntime / JevSurfacePolicy / JevGatewayClient) from this skill — they are FOREIGN_SURFACE in product-gate.
2. Treat exit 0 as a product PASS, a live-model availability proof, or a shipped-behavior verdict.
3. Force-release the live `devin-nova-answer-prompt` lease — it holds `ChatWorkflow.java` until 2026-10-09T17:32Z; OVERLAP means wait.
4. Add a `demo.jev.main-surface` knob — `demo.jev.surface.main.mode` already resolves in JevSurfacePolicy (and global `demo.jev.mode=off` dominates every surface override).
5. Inject `JevDecisionAdvisor` as a required bean — it is `@ConditionalOnProperty(conversate.enabled)`; use `@Autowired(required=false)` + null-guard like `ConversateApiCueService:46`.
6. Send `zeroDataRetention`, retry on 401/403/429, or grow `SearchDecisionService` regex/floor lists to chase eval scores — all are diff-forbid hits.
7. Wire the verdict mapping into OFF / FORCE_* branches — the advise hook belongs to AUTO only, after the decide call, and shadow mode may only write `chat.jev.main.verdict` / `chat.jev.main.agree` trace keys.
8. Run the verify-plan Gradle/live commands from this assist session before the patching session's final report — it only lists them.
