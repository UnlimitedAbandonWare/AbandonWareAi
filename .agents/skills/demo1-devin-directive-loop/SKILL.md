---
name: demo1-devin-directive-loop
description: Use when the user pastes an external agent (Devin) report asking how to reply, or asks to draft a demo-1 source-fix directive for handoff; delta-only follow-ups, evidence tiers, anti-regression close-out
---

# Demo1 Devin Directive Loop

Institutionalizes the 2026-09-21/22 madwain + ma33in handoff cycles: draft
directives, review external-agent reports, and close loops without regressing
to pre-fix instructions or re-auditing verified items.

## When

- User pastes a Devin/external-agent work report and asks what to answer.
- User asks for a source-modification directive (지시서) to hand to an external agent.
- A multi-round patch-review loop shows repeated friction (test-count disputes,
  provenance disputes, scope creep).

Not for: single-seam live debugging in this checkout (use the seam's primary
skill), Meta Display lens content, or PatchDrop bundle ingestion.

## Mode classification (pick exactly one first)

| Mode | Trigger | Output |
| --- | --- | --- |
| DRAFT | New ZIP/brief, directive needed | Directive doc with boundary + priority table |
| REVIEW | Agent report arrived, user asks for reply | Delta-only reply covering new gaps |
| CLOSE | Acceptance criteria met | Confirm under Project Root, update status doc, stop |

## REVIEW mode rules (delta-only)

1. Split the report's claims into the evidence tiers below; reply only to
   items whose tier is unproven or internally contradictory.
2. Never re-audit items already accepted with linked evidence; list them as
   kept (유지). Never widen scope to dissolve a blocked lane.
3. Every challenged claim gets at least one falsifying condition or a
   counterexample source (official docs with 확인일), not bare skepticism.
4. Check the recurring-pitfall catalog in
   `references/review-loop-patterns.md` before writing the reply; skip entries
   whose premise is absent from this report.
5. Reply skeleton (short, Korean unless the user asked otherwise):
   - 확인·유지할 성과 (1-2 lines)
   - Numbered remaining targets, each with: observation / why still unproven /
     exact verification or minimal fix / forbidden workarounds
   - Close conditions and report split: source edited / tests / runtime
     reflected / remaining blockers

## Evidence tiers (every claim maps to one)

- `live_success`: real app path, requestId-linked from input to delivery.
- `degraded_honest`: insufficient-evidence/fallback returned honestly.
- `provider_direct_only`: direct provider call succeeded; Java adapter/router
  path unproven.
- `auth_blocked`: owner session or credential required; never resolve by
  weakening security.
- `not_observed`: cause undetermined; leave as-is instead of guessing.
- Side labels: `billing_unverified` (billing not proven either way),
  `baseline_missing` (no comparison baseline), `historical` (ran before the
  final source state).

## Test-accounting integrity

- Bind each run to its XML path, command, exit code, and source state; never
  sum different runs into one pass count. State subset relations (B ⊂ F)
  explicitly.
- Mark pre-final-source runs `historical`; re-verify only the affected surface
  on final source.
- Preserve prior result dirs before reruns; distinguish cache/UP-TO-DATE from
  real execution.

## Provenance and authority

- A reverse-engineered original is `reconstructed_preimage`, never a preserved
  preimage. Lease/checkpoint-scope violations keep the original record and
  link to a new cycle; no overwrites.
- Git stays fully excluded in this loop, read commands included.

## DRAFT mode skeleton

1. Scope/integrity table (SHA-256, file counts) and unverified limits.
2. No-change boundary: Git forbidden, secrets/ports/namespaces preserved, do
   not redo already-fixed seams.
3. Priority table: confirmed / conditional (with trigger conditions) /
   not-reproduced.
4. Per item: evidence (file·line) → reproduce failure first → minimal fix →
   regression test → completion condition. Control cases mandatory (conditions
   that hide the symptom).
5. Report format: changed files + hashes, and a table separating mock / build /
   live boot / real API / browser evidence.

## Anti-regression close-out

- On CLOSE, record accepted results in `docs/PROJECT_STATUS.md` via
  `scripts/status_doc.py`; when an instruction changes, land it in AGENTS.md
  or skill BEGIN/END markers. The next session starts from the new baseline
  and never re-litigates closed items without new evidence
  (DEMO1-STALE-HANDOFF-REFERENCE).
- Follow `$demo1-goal-complete-stop` for termination. Do not expand work to
  erase a `not_observed` or `auth_blocked` item.

## References

- `references/review-loop-patterns.md` — recurring failure-pattern catalog
  with official-contract notes.
