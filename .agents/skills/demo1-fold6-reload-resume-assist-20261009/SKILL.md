---
name: demo1-fold6-reload-resume-assist-20261009
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, and verify-plan checks for the Codex brief FOLD6-RELOAD-RESUME-GEMINI-CUE R2 (Fold6 reload resume intent, same-owner caption preservation, audio diagnostics fields, gemini-cue in /chat model list). Product source stays with Codex.
---

# Fold6 reload-resume + gemini-cue catalog assist (2026-10-09)

## When

Codex is patching the 2026-10-09 R2 brief
(`PASTE_CODEX_fold6-reload-resume-gemini-cue_20261009_R2.txt`: WP1 reload
resume intent, WP2 same-owner caption preservation, WP3 diagnostics stage
fields, WP4 gemini-cue selectable in /chat) and the assist side needs the
anchor pins, the named-marker coverage check, the lease-overlap scope check,
the RED-before-product gate, or the ordered acceptance command list.

## SSOT

`var/codex-assist-fold6-reload-resume-20261009/README.md` (live-tree facts
vs brief claims, lease picture at spec write)

## Check

```
python -B scripts/fold6_reload_resume_assist_20261009.py pin --root .
python -B scripts/fold6_reload_resume_assist_20261009.py cover --root .
python -B scripts/fold6_reload_resume_assist_20261009.py scope --root .
python -B scripts/fold6_reload_resume_assist_20261009.py hypothesis --root .
python -B scripts/fold6_reload_resume_assist_20261009.py hold --root .
python -B scripts/fold6_reload_resume_assist_20261009.py verify-plan --root . [--contract fold6-reload-resume|gemini-cue-catalog]
python -B scripts/fold6_reload_resume_assist_20261009.py diff-forbid --root . --diff <owned.diff>
python -B scripts/fold6_reload_resume_assist_20261009.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-fold6-reload-resume-20261009/selftest_spec.py
```

`product-gate` unlocks product diffs only behind `red-boundary.json`
(boundary + 1-4 allowPaths inside PRODUCT; copy `red-boundary.example.json`).

## Do not

1. Edit product Java, product resources, display JS/HTML, or product tests from this skill.
2. Treat exit 0 as a product PASS, a live-model availability proof, or a real-glasses verdict.
3. Force-release any active lease on the hot paths; OVERLAP means wait.
4. Restore a sha12 after pin reports DRIFT. Re-read the file — Codex was mid-edit at spec write.
5. Use `/chat` page edits, `chat-ui.html`, `assets/interview/*`, or `ChatApiController` — they are FORBIDDEN_SURFACE in product-gate (WP4 needs only the yml allowlist + catalog/picker at most).
6. Change the pinned audio timeouts (35000/20000/6500), auto-start the mic on persisted pageshow, put resume intent in localStorage, resend dropped PCM, or ship cookie/auth/transcript/pcm fields in diagnostic events.
7. Flip `app.ai.allow-remote-model-selection` to true (it lives in `application.properties:665`, default false) or write snake_case `thinking_level` — the repo contract is camelCase `thinkingLevel`.
8. Run the verify-plan Gradle/node commands from this assist session before the main agent's final report — it only lists them.
