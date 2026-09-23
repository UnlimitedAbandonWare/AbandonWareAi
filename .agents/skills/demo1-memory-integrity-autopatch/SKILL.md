---
name: demo1-memory-integrity-autopatch
description: Use when demo-1 needs memory checksum, contaminated-context sampling
---

# Demo1 Memory Integrity Autopatch

## Core Rule

Keep the skill, scanner output, and default patch traces in the `MacSrc`
repository. Treat OneDrive copies as non-authoritative. The scanner is always
read-only; an `APPLY` verdict opens only the next guard, never source mutation
by itself.

## Select the Mode

| Observable request and evidence | Mode |
| --- | --- |
| Audit, review, directive, or failed safety gate | `AUDIT_ONLY` |
| Explicit Desktop-local implementation with owner/root proof | `DESKTOP_LOCAL_AUTOPATCH` |
| Explicit Notebook implementation for this checkout, unless the user selects another lane | `MACSRC_SMB_DIRECT` |

Do not infer mutation authorization from an analysis request. Do not use a
source-write rule to block reads, searches, web use, tools, or explicit
non-source output on other paths.

## Run the Probe

For explicit MacSrc direct mode, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-memory-integrity-autopatch\scripts\scan_memory_integrity_feedback.ps1 `
  -Root . -SourceWriteMode MacSrcSmbDirect `
  -OutputPath .\data\agent-handoff\memory-integrity\latest.json
```

Use `DesktopLocal` for a proven Desktop-local owner session and `AuditOnly` for
directive work. Read `references/feedback-contract.md` before consuming the
JSON and `references/source-directive.md` before selecting a patch item.

## Postprocess the Directive

Run `scripts/tri_query_directive_postprocess.ps1 -Mode Prepare`. Read
`references/tri-query-postprocess.md`, then evaluate `POSITIVE_QUERY` and
`NEGATIVE_QUERY` in separate contexts. Evaluate `NEUTRAL_QUERY` only after both
packets exist. It must compare A-B and B-A order, bind packet hashes, and write
only a candidate directive.

Run `-Mode Finalize` to validate schemas, input hashes, order stability, goal
score, and count-only secret results. The score is recomputed by the shared
`scripts/awx_goal_score_contract.ps1` helper; a submitted mismatch fails
closed. Finalize never overwrites the original directive or opens mutation.

## Gate the Result

- `HOLD`: edit no application source; report the first failure class.
- `AUDIT_ONLY`: emit a directive and `desktopFinalProof=evidence_needed`.
- `NO_PATCH_NEEDED`: stop without manufacturing work.
- `APPLY`: require a classified queue item, active source boundary, no index
  lock, no dirty target overlap without a recorded preimage, and zero secret
  hits.

In `MACSRC_SMB_DIRECT`, require `writePolicy.macSrcRootMatch=true` and
`writePolicy.requiredGuardSkill=demo1-macsrc-smb-direct-patch`. A Git
`dubious ownership` result may become `gitEvidenceMode=filesystem-cas`; never
change global `safe.directory`.

## Execute an Authorized Loop

For `MACSRC_SMB_DIRECT`, **REQUIRED SUB-SKILL:** use
`demo1-macsrc-smb-direct-patch`. Follow `Prepare -> Verify -> apply_patch ->
focused GREEN -> Complete`; use `Abort` after an intentional rollback or
interruption. The guard keeps its session under
`data/agent-handoff/macsrc-smb-direct/<runId>/` and shares the existing
PatchDrop lease namespace.

For Desktop-local work, use `demo1-desktop-autonomous-safe-patch` for one queue
item. Use `demo1-autonomous-patch-conductor` only when the explicit request
covers multiple items, and process at most four, one intent at a time.

For every item: observe RED, apply the smallest active-source patch, run the
focused GREEN command, rerun the scanner, and stop if the blocker or failure
class changes. Preserve `PromptBuilder.build(PromptContext)`. Do not auto-commit,
push, deploy, mutate DB/credentials, or change public APIs.

## Red Flags

- Choosing OneDrive because it is locally writable
- Blocking external reads or tools to enforce a source-write destination
- Mutating global Git trust configuration
- Treating scanner `APPLY` as direct permission
- Retaining raw context, prompts, keys, or transcript text as diagnostics
- Using unseeded samples for causal ablation
- Claiming Desktop runtime proof from Notebook, SMB, Browser, or prompt output

Any red flag changes the verdict to `HOLD`.

## Completion Evidence

Report the repo-local skill and guard paths, canonical MacSrc identity, scanner
verdict, selected item, exact commands and observations, pre/post hashes,
session path, rollback, and remaining `evidence_needed`.
