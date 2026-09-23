---
name: demo1-subsystem-patch-directive
description: "Use when modifying demo-1 subsystems: Overdrive, CFVM, MoE, ExtremeZ"
---

# Demo1 Subsystem Patch Directive

## Core Rule

Use this skill for implementation-level patches to the S01-S08 Dynamic RAG subsystems. Keep this entrypoint small: load the reference only for the subsystem being touched.

## Common Guardrails

- Patch active source only: `main/java`, `main/resources`, `src/test/java`, `src/test/resources`, `app/src/main/java_clean`, or `app/src/main/resources` unless Gradle proves another sourceSet is active.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)` or the existing equivalent boundary.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Preserve fail-soft behavior: subsystem failure must record a redacted reason and return to the existing RAG path.
- Never print raw secrets, raw prompts, raw queries, Authorization headers, cookies, or API keys.
- Include `sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}` and `sbp_[A-Za-z0-9_-]{10,}` in count-only secret scans; never print matched text.
- Do not merge by simple class name; confirm package and FQCN before editing.

## Reference Routing

Read `references/subsystem-patch-directive-reference.md` only for the requested subsystem section:

| Trigger | Reference Section |
| --- | --- |
| Overdrive, Anchor-Based Context Compression | `S01 - Anchor-Based Context Compression (Overdrive)` |
| CFVM, Failure Pattern Analysis, RawMatrixBuffer | `S02 - Failure Pattern Analysis (CFVM)` |
| MoE, Strategy Selector, ArtPlate, Critic | `S03 - MoE Strategy Selector` |
| Matryoshka, embedding slicing, vector dimensions | `S04 - Matryoshka Slicing` |
| ExtremeZ, Massive Parallel Query Expansion, query burst | `S05 - ExtremeZ / Massive Parallel Query Expansion` |
| HYPERNOVA, TWPM, CVaR, Risk-K | `S06 - HYPERNOVA` |
| CIH-RAG, IQR, MLA Breadcrumb, Abara | `S07 - CIH-RAG / 집중 탐침 (Abara)` |
| OpenAI adapter, local LLM, version purity | `S08 - Version Purity / OpenAI Adapter` |
| Cross-subsystem integration or final report shape | `서브시스템 연동 순서`, `전체 검증 명령 세트`, `최종 보고` |

## Workflow

1. Confirm Desktop root, active sourceSets, declared-target preimage state, any real index/ref operation in progress, and PatchDrop/source-edit lock state.
2. Identify the subsystem section from the trigger table.
3. Read only that section from `references/subsystem-patch-directive-reference.md`; read integration/final-report sections only when the patch spans multiple subsystems.
4. Add or use a focused RED/characterization test before behavior changes when practical.
5. Patch the smallest active file branch that satisfies the subsystem contract.
6. Run the narrowest verification from the reference section, then broaden only when the changed surface crosses subsystem boundaries.
7. Report remaining `evidence_needed` rather than inventing Browser, Computer, Supabase, Mac mini, Notebook, or PatchDrop proof.

## Output Contract

Report:

```text
subsystem: S01|S02|S03|S04|S05|S06|S07|S08|cross-subsystem
referenceSectionsRead: section names
activeSourceProof: source_scan or Gradle sourceSet evidence
patchScope: files changed
traceKeys: required keys checked or evidence_needed
verification: commands and observed result
browser: optional | evidence_needed | verified
computer: optional | evidence_needed | verified
supabase: read_only_evidence_needed | verified_project_scoped_readonly
evidence_needed: missing artifact / verify with exact command
```

Do not claim build, boot, Browser, Computer, Supabase, Mac mini, Notebook, or PatchDrop proof unless current command output proves it.
