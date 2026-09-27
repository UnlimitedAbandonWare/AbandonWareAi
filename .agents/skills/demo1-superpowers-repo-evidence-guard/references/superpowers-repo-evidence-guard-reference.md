# Superpowers Repo Evidence Guard Reference

## Authority Order

For demo-1, keep this order:

1. Current source files and actual command output.
2. Root `AGENTS.md`, `.agents/skills`, and active `agent-prompts`.
3. User-provided goal text and attachments.
4. Official vendor docs for changing external APIs.
5. Memory, prior runs, or stale reports.

If a Superpowers skill asks for broad ceremony but the repo has an exact Safe
Patch seam, keep the ceremony minimal and preserve the repo gate.

When a repo-local skill-family validator summary is available, treat its compact
fields as repo evidence. Read `goalCompletionClaimAllowed`,
`fullReportRecommended`, and `summaryReuseCondition` before letting a process
skill claim completion or reopen a full report. If
`goalCompletionClaimAllowed=false`, keep the active goal open. If
`fullReportRecommended=false`, reuse the compact summary until
`summaryReuseCondition` changes.

## Process Skill Mapping

- brainstorming: clarify ambiguous multi-lane goals, then keep the pasted goal as the design authority when the user explicitly says to make the artifact.
- test-driven-development: add RED tests before behavior changes; for pure reference skills, validate structure and pressure scenarios instead.
- writing-skills: keep skills short, triggerable, and validated.
- verification-before-completion: run concrete commands before claiming success.

## Repo Gates That Outrank Process

- active sourceSet proof
- Desktop canonical final proof
- secret redaction and count-only scans
- `dev.langchain4j:*` version `1.0.1`
- final prompt construction at `PromptBuilder.build(PromptContext)` or existing equivalent
- Supabase read-only/project-scope boundary
- PatchDrop manual handoff safety

## Verification Fields

- Superpowers skill used
- repo evidence that overrode or narrowed process guidance
- commands run
- remaining `evidence_needed`
