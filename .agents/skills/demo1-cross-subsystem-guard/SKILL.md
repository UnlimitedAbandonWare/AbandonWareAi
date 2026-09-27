---
name: demo1-cross-subsystem-guard
description: Use when a demo-1 patch touches StrategyConflictResolver, ExecutionPlan, ExtremeZ
---

# Demo1 Cross Subsystem Guard

## Purpose

Prevent a Safe Patch in one Dynamic RAG subsystem from silently breaking another. Use this before editing shared orchestration files and before claiming a multi-subsystem patch is complete.

## Reference Routing

Load `references/cross-subsystem-guard-reference.md` only when you need the
file-pattern test matrix, required TraceStore keys, broad Gradle command, or
report shape.

## Stop Rules

- Do not modify two or more cross-subsystem files in one patch cycle unless the user explicitly asks or a compile/runtime blocker proves it necessary.
- Do not patch inactive mirrors. Prove active sourceSet ownership first.
- Do not bypass `PromptBuilder.build(PromptContext)`.
- Do not introduce raw secret, prompt, query, or Authorization values into logs, TraceStore, SSE, HTML, or tests.
- Do not proceed if a required focused test for an affected subsystem is missing; report `evidence_needed`.

## Workflow

1. List changed target files and map each to S01-S08.
2. If two or more subsystems are affected, read the reference and write the
   focused test matrix before editing.
3. Patch the smallest confirmed seam.
4. Run focused tests for every affected subsystem.
5. Run broad gates from the reference only after focused proof passes.
6. Run an active-root secret scan that reports count only.

## Report

Return the reference report shape: files changed, affected subsystem IDs,
focused tests, broad gates, count-only secret result, and remaining
`evidence_needed`.
