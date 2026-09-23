---
name: demo1-devin-source-orchestrator
description: Use when Devin or another agent gets a pasted multi-seam demo-1 source brief and must pick skills and tools per phase instead of one skill or thirty @mentions
---

# Demo1 Devin source orchestrator

Pasted product briefs (hint-context + Fold listen, video + settings + late-response) are **several seams**. Do not collapse them into one skill, and do not @ every skill. Classify once, then run **one skill per phase** with the tools that phase names.

## Command

```text
python -B scripts/devin_task_orchestrate.py plan --brief-file <path>
python -B scripts/devin_task_orchestrate.py next --plan <plan.json> --done <phaseId>
python -B scripts/devin_task_orchestrate.py capture --role wear --invoke [--task <taskId>]
python -B scripts/devin_task_orchestrate.py compare --before <snap> --after <snap>
python -B scripts/devin_task_orchestrate.py self-test
```

`--brief` / stdin also work. Output is JSON only. Playbooks: `playbooks/playbooks.json`. Typed routes stay in `.agents/skills/INDEX.md`.

## How to run a plan

1. Task entry: `python -B scripts/agent_preflight.py --root .` then `$demo1-work-ledger` open. Foreign `in_progress` journals are liveness-unknown, not done.
2. `plan` on the **current user brief** (not an old handoff). Follow `phases` in order.
3. Each phase: load **that** `skill` (+ optional `guard`), run listed `tools`, stay inside `write`. `skip` is binding. `stop` is the evidence that ends the phase.
4. `next --done …` after a phase. Do not start the next write scope until the current `stop` is met.
5. Independent seams may run sequentially with disjoint write targets. Shared read is allowed. Same path in two live leases = skip that file.
6. Acceptance of the **phase** is not acceptance of the whole brief. Whole-brief stop uses `$demo1-goal-complete-stop`.

## Fold / glasses debug snapshots

`var\debug` is bounded and not a handoff. `capture` writes redacted status to `data/agent-handoff/display-debug/latest.json` (and `<taskId>/debug-snapshots/` when `--task` is set). Display/hint/listen briefs insert `capture-before` and `capture-after` automatically.

- Run `--invoke` after the user reproduces on Fold; `--invoke` is read-only `Debug-Meta-Display.bat -Action status -Json` (no restart).
- Read `patchHints` before choosing a write seam. Compare before/after with `compare`.
- Record last-audio / last-transcript **times** from Fold poll/status if the BAT JSON lacks them. Never copy transcript text or keys.
- A later patch that ignores `latest.json` when a wear-test just happened is incomplete.

## Method (every source-edit brief)

- Trace the live path before patching: receive → assemble → optional search → generate → verify/retry/fallback → adopt → display.
- Separate leftover **on-screen** text from a **new** model generation. Confirm request id, model, included past, and settings version on the adopted response.
- Storage vs hint-input window are different. Do not wipe transcripts to shrink a prompt.
- Prefer recent-utterance + time/size caps + explicit reset **before** a new memory framework or a per-turn LLM topic judge.
- Primary, verify, retry, and fallback share one snapshot of settings + selected past. A fallback must not restore already-excluded past.
- Late responses: reuse existing request/utterance/`hintId` gates; `CompletableFuture.cancel` is not enough; do not void every in-flight cue on a one-character transcript edit.
- Keep unrelated display TTL, hint **output** length, model routing, and cue period unless the live path proves they cause this defect.

## What this is not

Not `$demo1-core-request-router` (classifies; this sequences). Not `$demo1-long-think-goal-composer` (goal contract, no live tool plan). Not `$objective-executor` (human flow; call **this tool** when the brief has two seams). Does not grant lease/preimage/APPLY.

## Devin paste

`@objective-executor @demo1-devin-source-orchestrator` plus the brief. Run `plan` before editing. Daily chip lists: `.devin/PROMPTS/`.
