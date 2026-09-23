---
name: demo1-desktop-only-proof-loop
description: Use when demo-1 work asks whether Desktop-local source, script, Gradle
---

# Demo1 Desktop Only Proof Loop

## Core Rule

Treat Desktop canonical proof as the default completion lane for source patches in `C:\AbandonWare\demo-1\demo-1\src`. External host, UI, and Supabase evidence stays supporting unless the user or changed surface explicitly requires it.

## Reference Routing

Read `references/desktop-only-proof-loop-reference.md` only for preflight
commands, verification commands, output fields, or blocker labels.

## Decision

Use `decision=desktop_only_ready` only when all are true:

- Current root is the Desktop canonical root.
- Active sourceSets are proven by `source_scan` or Gradle sourceSet checks.
- No active top-level PatchDrop patch blocks Desktop source ownership.
- The task is source/script/prompt-local and does not explicitly require Mac mini, Notebook, Browser, Computer, or Supabase final proof.
- Narrow verification exists or has a concrete command.

Use `supporting_evidence_missing` for absent Mac mini, Notebook, Browser, Computer, or Supabase evidence that is not required by the current patch.

Use `evidence_needed` with an exact command when proof is actually required and missing.

## Verification And Output

Use the reference file for the narrow verification ladder and report fields.
Keep Browser, Computer, and Supabase probes out of the default ladder unless the
changed surface touches UI automation, Windows UI, or live DB proof.
Keep `supportingEvidenceMissing` explicit for optional Mac mini, Notebook,
Browser, Computer, or Supabase gaps.

Do not convert missing supporting evidence into fake `OK`.
