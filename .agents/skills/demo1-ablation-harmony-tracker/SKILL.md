---
name: demo1-ablation-harmony-tracker
description: "Use when quantitative metric normalization has finished"
---

# Demo-1 Ablation Harmony Tracker

Turn an ablation report into a source-backed Safe Patch directive: identify the
subsystem pair that breaks harmony, then name the trace keys and proof gate
needed before code changes.

## Operating Order

1. Run or read `quantitative-metric-normalizer` output.
2. Classify harmony breaks and design ambiguities.
3. Read `references/ablation-harmony-reference.md` only for the needed
   subsystem map, H01-H08 board, verification commands, or report shape.
4. Use `demo1-subsystem-patch-directive` for algorithm-body detail.
5. Use `demo1-autonomous-patch-conductor` after patch lane and gates are clear.

## Non-Negotiables

- Work from Desktop root `C:\AbandonWare\demo-1\demo-1\src`.
- Live source and command output outrank prompts, memory, attachments, and
  producer logs.
- Reconfirm active sourceSets; keep LangChain4j `1.0.1` and the
  `PromptBuilder.build(PromptContext)` boundary.
- Never print raw secrets, auth headers, cookies, raw prompts, raw env dumps, or
  sensitive queries.
- Missing source evidence becomes `evidence_needed`, not a guessed directive.

## Evidence Order

Prefer Desktop source/Gradle output, then the current scorecard or directive,
then PatchDrop evidence, then memory. If an attachment is mojibake, recover only
stable identifiers such as file paths, trace keys, subsystem names, failure
classes, Gradle commands, and P0/P1/P2 labels.

## Decomposition Choice

Use 3-way Self-Ask only when subsystem owner, canonical authority, and failure
evidence all matter. Use direct mode for exact missing TraceStore keys, one-file
syntax fixes, duplicate YAML keys, or tests that already identify the seam.

## Patch Gate

Before writing code, prove active sourceSet, canonical class or caller,
observable trace/behavior gap, smallest reversible patch, and known verification
command. If any item is missing, return a read-only `evidence_needed` directive.

## Reference Routing

| Need | Reference section |
| --- | --- |
| Owner anchors before editing | Subsystem Map |
| H01-H08 IDs and required traces | Harmony Break Board |
| Aggregate score caveat | Optional Aggregate Scores |
| Desktop Gradle commands | Required Verification |
| Report shape | Output Format |

Reference file: `references/ablation-harmony-reference.md`

## Output Contract

Use `Output Format` in the reference file. Always include decomposition,
selected harmony break, active source proof, patch intent, proof command, and
separate Browser, Computer, and Supabase lane status. Never claim build, boot,
provider, browser, or prompt success without command output.
