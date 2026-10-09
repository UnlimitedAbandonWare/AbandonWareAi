---
name: demo1-nova-stability-assist-20261009
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, and verify-plan checks for the Codex brief NOVA-STABILITY-AUDIT-20261009-v1 (WP1 late-renewal capture fence confirmed defect; WP2 search-off retry classification; WP3 AUTO default candidate eligibility; WP4 settings-save vs in-flight generation boundary). Product source stays with Codex.
---

# Nova stability audit assist (2026-10-09)

## When

Codex is patching the 2026-10-09 brief
(`PASTE_CODEX_nova_stability_audit_20261009.txt`: WP1 renewal generation
fence, WP2 search-off retry error classification, WP3 AUTO default
candidate eligibility, WP4 settings-save vs generation boundary) and the
assist side needs the anchor pins, the named-marker coverage check, the
lease-overlap scope check, the RED-before-product gate, or the ordered
acceptance command list.

## SSOT

`var/codex-assist-nova-stability-20261009/README.md` (live-tree facts vs
brief claims, lease picture at spec write)

## Check

```
python -B scripts/nova_stability_assist_20261009.py pin --root .
python -B scripts/nova_stability_assist_20261009.py cover --root .
python -B scripts/nova_stability_assist_20261009.py scope --root .
python -B scripts/nova_stability_assist_20261009.py hypothesis --root .
python -B scripts/nova_stability_assist_20261009.py hold --root .
python -B scripts/nova_stability_assist_20261009.py verify-plan --root . [--contract wp1-renewal-generation-fence|wp2-4-focus-model-settings]
python -B scripts/nova_stability_assist_20261009.py diff-forbid --root . --diff <owned.diff>
python -B scripts/nova_stability_assist_20261009.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-nova-stability-20261009/selftest_spec.py
```

`product-gate` unlocks product diffs only behind `red-boundary.json`
(boundary + 1-4 allowPaths inside PRODUCT; copy `red-boundary.example.json`).

## Do not

1. Edit product Java, product resources, display JS/HTML, or product tests from this skill.
2. Treat exit 0 as a product PASS, a device/glasses verdict, or a JVM loaded-build proof.
3. Force-release any active lease on the hot paths; OVERLAP means wait. `devin-nova-answer-prompt` (default.lock, deferred prompt feature) holds the WP2-WP4 surface until ~17:32 UTC — per directive H4 Codex holds those product edits and continues independent verification.
4. Restore a sha12 after pin reports DRIFT — re-read; Codex is mid-edit (WP1 RED cases already landed, fence fix pending).
5. Touch `index.html`, `NovaFocusSettings.java`, `NovaFocusHistoryService.java`, `PromptContext`, `StandardPromptBuilder`, `ChatConversationContext`, `ChatWorkflow`, `application*.yml/properties`, `chat.js`, `chat-ui.html`, `assets/interview/*`, `ChatApiController` — all are outside the WP-named surface (EXTRA_PRODUCT or FORBIDDEN_SURFACE).
6. Let WP2-WP4 bypass the evidence gate: no RED fixture or policy proof = verification-gap report only, no source edit. `retryable=true` or bare RuntimeException is never proof of a search failure.
7. Hardcode tailHoldMs (3/5s stay user settings via `display-focus-controls.js` 초↔ms), flip `hintsEnabled`, force `isFallback=true`, set `settingsVersion` to a literal, or weaken `accepts()` CAS — diff-forbid flags these on added lines.
8. Reuse `av-preset`/`av-phrases`/`nf-wake` semantics or add `answerPrompt`/`nf-prompt` — the prompt feature is user-deferred; `nf-wake`/`av-*` already exist for other contracts.
9. Run the verify-plan Gradle/node commands from this assist session before the main agent's final report — it only lists them. Blanket test suites stay forbidden; no server restart/port cleanup to close the JVM loaded-build gap.
10. Re-cite past Java194/JS188/JS81/Java127 runs as this run's verdict; a PID change after the announced reboot is not a defect.
