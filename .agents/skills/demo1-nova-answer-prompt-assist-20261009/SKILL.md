---
name: demo1-nova-answer-prompt-assist-20261009
description: Read-only pin, coverage, lease overlap, hypothesis, hold-ledger, diff-review, and verify-plan checks for the DEVIN brief PASTE_DEVIN_nova-answer-prompt_20261009 (Nova Focus 답변 지침 입력 칸 + 일반/면접 프리셋 — answerInstruction/answerPreset persisted in settingsJson, injected into the focus answer prompt only). Product source stays with the patching session.
---

# Nova answer-prompt assist (2026-10-09)

## When

A patching session (the "ambitious-request" Devin session, journal
`devin-nova-answer-prompt-ba8a924e`, lease `default`) applies
`PASTE_DEVIN_nova-answer-prompt_20261009.txt`: `answerInstruction` +
`answerPreset`(GENERAL/INTERVIEW/CUSTOM) in `NovaFocusSettings`, persisted via
`settingsJson`, effective-instruction evaluated in `NovaFocusService`, injected
only under `if (focusOutput)` in `StandardPromptBuilder` as a
`사용자 답변 지침:` block. This skill holds the anchor pins, the named-marker
coverage check, the lease-overlap scope check, the protected-surface diff
guards, the hypothesis/HOLD ledgers, and the ordered acceptance command list.

## SSOT

`var/codex-assist-nova-answer-prompt-20261009/README.md` (live-tree facts vs
brief claims, claim/drift table, S1 av-preset answer, lease picture at spec
write). `baseline.json` = mid-edit sha256 snapshot (backend applied, 22:4x KST).

## Check

```
python -B scripts/nova_answer_prompt_assist_20261009.py pin --root .
python -B scripts/nova_answer_prompt_assist_20261009.py cover --root .
python -B scripts/nova_answer_prompt_assist_20261009.py scope --root .
python -B scripts/nova_answer_prompt_assist_20261009.py hypothesis --root .
python -B scripts/nova_answer_prompt_assist_20261009.py hold --root .
python -B scripts/nova_answer_prompt_assist_20261009.py verify-plan --root .
python -B scripts/nova_answer_prompt_assist_20261009.py snapshot --root .   # baseline 갱신
python -B scripts/nova_answer_prompt_assist_20261009.py diff-review --root .
python -B scripts/nova_answer_prompt_assist_20261009.py diff-forbid --root . --diff <owned.diff>
python -B var/codex-assist-nova-answer-prompt-20261009/selftest_spec.py
```

`pin` FRESH / `cover` COVERED = anchors and named markers matched — never a
product PASS. `diff-review` CHANGED on a budget file is normal mid-work;
CHANGED on a protected file is the violation signal (PROTECTED_TOUCHED, exit 4).

## Do not

1. Edit product Java, product resources, chat.js, display/interview/meta assets, or `NovaFocusState.java` from this lane — the whole patch surface is the main session's `default` lease (14 files, owner `devin-nova-answer-prompt`).
2. Treat exit 0 as a product PASS, a compile result, or a shipped-behavior verdict — this lane never runs Gradle, the server, or a model call.
3. Force-release the live `default`/`nova-stability-6138d1bb` leases — OVERLAP means wait.
4. Wire `av-preset` / `AutoVoiceTrigger` / `대화 목적` into the Nova answer path — that preset is auto-voice-hint-only (`ConversateCardPrompt` greeting + `ConversateSessionService` disarm); coupling is a diff-forbid hit.
5. Repurpose `ld-quiet`/`ld-cooldown`/`ld-force` keys or the general hint prompt (`ConversateCardPrompt`/`LensDisplayPrefs`) — diff-forbid hits.
6. Log the instruction text (`log.*instruction` on a Java added line = diff-forbid hit); trace carries preset/chars/sha8 only.
7. Let the injection escape `if (focusOutput)` — the block must stay Nova-Focus-only; ordinary `/chat` prompt must not gain `사용자 답변 지침:`.
8. Run the verify-plan Gradle/reload/live commands from this assist session before the patching session's final report — it only lists them. `--ready`≠READY before a live focus probe; public-site sends stay 0; model calls ≤5; restore the user profile settings after.
