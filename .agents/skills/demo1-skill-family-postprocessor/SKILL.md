---
name: demo1-skill-family-postprocessor
description: "Use when demo-1 repo-local skill families need postprocessing, validation"
---

# Demo1 Skill Family Postprocessor

Use this skill after creating, splitting, or repairing repo-local demo-1 skills.
Treat skills as artifacts, not runtime source. Keep edits under `.agents/skills`
unless live command output proves another active surface owns the failure.

## Core Rule

Run the validator, fix only reported skill-family issues, and persist compact
redacted evidence. Do not invoke Browser, Computer, Supabase, or other external
lanes unless the edited skill specifically needs live proof from that lane.
Include `$demo1-artifact-trace-curator` when validating that leftover-artifact
classification stays inventory-only; validation ownership remains here.
Treat a missing, stale, unreadable, or not-ok validator self-test status as
blocking evidence. Only a current successful self-test may admit
`artifactCompletionStatus=artifact_ready`.

## Reference Routing

Load only the section needed for the current pass:

| Need | Reference section |
| --- | --- |
| Run validator variants | `Validator Commands` |
| Understand expected lanes | `Default Family` |
| Classify report failures | `Failure Labels` |
| Summarize output fields | `Output Contract` |
| Preserve proof hygiene | `External Lane Defaults` |

Reference file:
`references/skill-family-postprocessor-reference.md`

## Standard Workflow

1. Confirm Desktop root. Do not treat `index.lock` or `git status` as a
   skill-edit gate (`AGENTS.md` `DEMO1-GIT-LOCAL-FIRST`).
2. Run the validator with `-SummaryJson` first for compact status.
3. If `validatorSelfTestStatus.ok=false`, run its exact `verifyWith` command;
   do not infer readiness from the remaining green counts.
4. Run with `-ReportPath` and `-HistoryDir` when durable evidence is needed.
5. Use `-DiscoverPrefix demo1-` to audit sibling repo-local skills without
   adding them to the required family gate.
6. Use report-only line and word budgets to find context-reduction candidates.
7. Fix only concrete findings: missing `SKILL.md`, invalid frontmatter, broken
   `agents/openai.yaml`, scaffold marker counts, secret-pattern counts, lane
   coverage gaps, or over-required external proof.
8. Validate every changed skill with `quick_validate.py`.
9. Re-run `-SummaryJson`; open the full report only when counts require detail.
10. Keep the final report lane-explicit: Browser optional, Computer optional,
   Supabase read-only evidence-needed unless project-scoped proof exists, and
   Superpowers supporting process only.

## Required Family Lanes

- Long 5-9 hour objective intake:
  `demo1-long-think-goal-composer`
- Desktop-only proof:
  `demo1-desktop-only-proof-loop`
- PatchDrop manual default:
  `demo1-patchdrop-manual-default`
- Browser, Computer, and Supabase evidence:
  `demo1-demand-driven-external-proof`
- Codex usage reduction:
  `demo1-codex-usage-triage`
- Superpowers boundary:
  `demo1-superpowers-repo-evidence-guard`
- Family validation and postprocessing:
  `demo1-skill-family-postprocessor`

## Common Verification

Use `Validator Commands` in the reference file for `-SummaryJson`, skill-only
validation, persisted reports, discovery-fail mode, budget-fail mode, and report
field definitions.
