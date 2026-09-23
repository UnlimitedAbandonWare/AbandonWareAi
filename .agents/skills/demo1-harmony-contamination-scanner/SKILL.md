---
name: demo1-harmony-contamination-scanner
description: Use when scoring demo-1 Dynamic RAG harmony health, contamination risk
---

# Demo1 Harmony Contamination Scanner

Produce a source-backed harmony score for the demo-1 Desktop canonical root
before, during, or after autonomous Safe Patch work. Treat prior scores as a
baseline only; recompute from the live active sourceSet.

## Intake

Run from `C:\AbandonWare\demo-1\demo-1\src`. Prefer active roots:
`main/java`, `main/resources`, `src/test/java`, `app/src/main/java_clean`, and
`app/src/main/resources`.

If Git metadata is absent, report that evidence state and continue with
filesystem and Gradle checks.

## Metrics

Collect low-risk, source-backed counts only:

- `silentCatchRatio`: empty catch and catch-without-breadcrumb ratio.
- `duplicateFqcn`: duplicate fully-qualified Java type names in active roots.
- `crossSubsystemFiles`: files referencing two or more S01-S08 keyword groups.
- `traceCoverage`: required TraceStore keys present in active seams.
- `harmonyBreaks`: HB-01 through HB-12 status with source evidence.
- `secretPatternHits`: count only; never print matched values.

## Reference Routing

Read `references/harmony-contamination-reference.md` only when you need:

| Need | Reference section |
| --- | --- |
| S01-S08 keyword groups | Subsystem Keywords |
| HB-01 through HB-12 deductions | Score Board |
| JSON output example | Output Shape |
| Desktop Gradle commands | Verification |

## Score Rule

Start from 100, subtract open HB break scores from the reference board, and
clamp final score to `[0, 100]`. Mark a break `DONE` only when a source contract
and focused test prove the fix.

## Output Contract

Use `Output Shape` in the reference file. Include score, low-risk counts,
HB status/evidence, and count-only secret results. Use `evidence_needed` for
missing proof and keep external lanes separate from source scans.

## Verification

Use the narrowest useful proof first. For skill-only postprocessing, run
`quick_validate.py` and the skill-family validator. For runtime score changes,
use the Desktop Gradle commands in the reference file.
