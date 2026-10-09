---
name: demo1-lens-fold6-assist-20261008
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, and verify-plan checks for the Codex briefs LENS-CONCISE-SPEED-20261008 (lens link/source separation, Gemini 3.8 Flash, Fast/Standard/Deep, display latency) and FOLD6-LENS-RECOVERY-20261008 (capture/stop/reconnect/epoch/provider recovery matrix). Product source stays with Codex.
---

# Lens links + Fold6 lens recovery assist (2026-10-08)

## When

Codex is patching either of the two 2026-10-08 briefs and the assist side needs the anchor pins, the named-test coverage check, the lease-overlap scope check, the RED-before-product gate, or the ordered acceptance command list.

## SSOT

`var/codex-assist-lens-fold6-20261008/README.md` (live-tree facts vs brief claims, lease picture at spec write)

## Check

```
python -B scripts/lens_fold6_assist_20261008.py pin --root .
python -B scripts/lens_fold6_assist_20261008.py cover --root .
python -B scripts/lens_fold6_assist_20261008.py scope --root .
python -B scripts/lens_fold6_assist_20261008.py hypothesis --root .
python -B scripts/lens_fold6_assist_20261008.py hold --root .
python -B scripts/lens_fold6_assist_20261008.py verify-plan --root . [--contract lens-links-speed|fold6-lens-recovery]
python -B scripts/lens_fold6_assist_20261008.py diff-forbid --root . --diff <owned.diff>
python -B scripts/lens_fold6_assist_20261008.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-lens-fold6-20261008/selftest_spec.py
```

`product-gate` unlocks product diffs only behind `red-boundary.json`
(boundary + 1-4 allowPaths inside PRODUCT; copy `red-boundary.example.json`).

## Closeout pointers

- 마감 판정은 `scripts\goal_closeout_classify.py`(AGENT_BLOCKING/USER_ONLY/EXTERNAL 3분류) — USER_ONLY·EXTERNAL만 남으면 blocked가 아니라 complete+목록.
- 런처 `project-settings-load-failed`는 이제 `stage=<read|parse|validate|apply>;source=<파일명>;kind=<IOException|JsonParse|MissingField|Locked>` 접미사로 원인을 읽는다(`scripts\use_project_keys.ps1`).

## Do not

1. Edit product Java, product resources, display JS/HTML, or product tests from this skill.
2. Treat exit 0 as a product PASS, a live-model availability proof, or a real-glasses verdict.
3. Force-release any active lease (`lens-links-20261008-*`, `fold6-lens-recovery-20261008-*`, `devin-nova-focus-lens-blackout-*`); OVERLAP means wait.
4. Restore a sha12 after pin reports DRIFT. Re-read the file — Codex was mid-edit at spec write.
5. Use `/chat`, `chat-ui.html`, or `assets/interview/*` as the evidence surface — they are FORBIDDEN_SURFACE in product-gate.
6. Tighten the 1 s lens poll, auto-restart the mic on pageshow, resend PCM, or add camera paths outside `display-snapshot.js`.
7. Run the verify-plan Gradle/node commands from this assist session before the main agent's final report — it only lists them.
