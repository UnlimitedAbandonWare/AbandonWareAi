---
name: demo1-agy-depth-router
description: 'agy 작업 시작 시 난이도 L1~L3를 한 번 판정하고, 어려울수록 검증 반복·서브에이전트·깊은 질의를 더 쓰는 라우터. 점수 기준은 configs/agy-depth.json.'
---

# demo1 agy depth router

Judge the task's depth **once at start**, print `[depth L1|L2|L3: <1-line reason>]`
as the first line of the reply, then work at that level. Thresholds and caps live
in `configs/agy-depth.json` — re-read it before applying the numbers below.

## Scoring (each axis 0–2, total 0–10)

1. **Claims**: count of file:line / numeric claims that must be verified.
   0=few or none, 1=3–7, 2=8+.
2. **Scope**: files/modules touched. 0=1–2, 1=3–6, 2=7+ or cross-subsystem.
3. **Overlap risk**: chance of colliding with another agent's lease, uncommitted
   foreign changes, or live sessions. 0=none, 1=possible, 2=active leases nearby.
4. **Ambiguity**: multiple attachments, open-ended ask, unstated acceptance.
   0=crisp, 1=some gaps, 2=must invent the contract.
5. **Blast cost**: protected files, hard-to-revert edits, output another agent
   will implement from. 0=trivial, 1=moderate, 2=directive/report downstream.

Bands (from json `lBands`): total ≤ `l1Max` → **L1**; ≤ `l2Max` → **L2**;
else **L3**. Directive/brief writing for other agents is **at least L2**.

## L1 — light (same as today)

Answer directly. Verify each load-bearing fact **once**; if it can't be checked
cheaply, mark it `확인 필요` instead of asserting.

## L2 — medium (~1.5–2x of L1)

- Read `var/agy-seed/latest.md` first if present (session seed: git status,
  active leases, recent handoffs — skip sections not relevant).
- Re-open every cited file/line with Select-String or a bounded read; quote
  what you actually saw, not what you remember.
- When the draft is done, run the self-review once using
  [references/depth-checklist.md](references/depth-checklist.md).

## L3 — heavy (~2.5–3x, do not economize)

Everything in L2, plus:

- **fact-verifier** (custom agent in `.agents/agents/`): hand it the *claim
  list only* — each claim as `file:line` or a runnable command — and collect
  SAME/MOVED/WRONG/UNVERIFIABLE per claim. Parallelize independent claims.
- **red-team-reviewer** (custom agent): adversarial pass over the draft for
  missing steps, protected-file violations, unmeasurable acceptance, secret
  leakage. Max 10 lines of findings.
- Loop: rewrite until **zero** `likely`/`아마`/`probably` remains in claims,
  at most `l3.maxRounds` (default 3) rounds. Remaining unknowns are written
  as `미확인` with the check that would resolve them.
- One deep side-question per sticking point may go to
  `scripts\agy_deep.ps1 -Question "..."` (separate agy print call at Flash's
  top effort, capped at `l3.deepCallsPerSession` per session, logged to
  `var/agy-depth/calls.jsonl`).
- Tell the user **once** (first line block): "L3 — `/effort` won't go higher
  on Flash (already at top); depth is coming from extra verification passes."

## Escalation rules

- Level only goes **up**: new facts mid-task may raise L2→L3; never lower.
  Record the reason in one line.
- **Waste guards**: never re-read the same file range (cite the first result);
  subagents get the claim list, never the whole conversation; do not spawn a
  subagent to check something you can grep in one call.
- If `enable-deepagent` ever becomes a public setting, prefer the router's own
  steps — the internal flag is not touched (see references note).

## Non-goals

Not a model router — model stays on the latest same-tier Flash id via
`agy_model_latest.ps1 -Pick` (profile=depth in Start-Agy-CLI.bat). Not a
permission grant — source edits still need the existing lease/checkpoint gates.
