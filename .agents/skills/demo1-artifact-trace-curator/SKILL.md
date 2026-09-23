---
name: demo1-artifact-trace-curator
description: "Use when files remain under an explicit demo-1 data/agent-handoff"
---

# Demo1 Artifact Trace Curator

## Core Boundary

Inventory one explicit trace subroot or recheck one published trace against an
independently retained manifest SHA. Never
delete, move, quarantine, apply, unlock, repair, approve, or reinterpret an
input artifact as stronger proof than its verified binding permits.

This skill writes one new capture or verification directory under
`data/agent-handoff/`. In every packet,
`mutationAllowed=false` and `deleteAuthorized=false` refer to the inventoried
files and all application/runtime surfaces.

Read `references/artifact-trace-contract.md` before running the helper.

## Trigger

Use this skill when:

- completed work left session, verification, completion, tombstone, diagnostic,
  or PatchDrop files whose meaning needs a bounded inventory;
- an old lock-cleanup tombstone could be confused with patch success;
- a producer bundle or `.ready` marker could be confused with Desktop apply;
- retention review needs `preserve` versus `prune_candidate` without authorizing
  deletion; or
- a compact hash-only handoff is needed without raw log or query content.
- a trace may have changed or been tampered with after publication; or
- `UNCHANGED`, `CHANGED`, `INDETERMINATE`, or `INVALID` needs bounded recheck.

## Non-Trigger

Do not use it to clean stale locks, remove files, adjudicate a terminal patch,
apply PatchDrop, verify runtime/provider lineage, or validate skill structure.
Route those tasks respectively to:

- `$demo1-macsrc-smb-direct-patch`;
- the existing PatchDrop janitor/orchestrator;
- `$demo1-macsrc-patch-postprocessor`;
- Desktop runtime verification; or
- `$demo1-skill-family-postprocessor`.

## Capture

Use a proven repository root and a caller-selected strict descendant of
`data/agent-handoff/` or `__patch_drop__/`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\new_artifact_trace_manifest.ps1 `
  -Root . `
  -TraceId <lowercase-slug> `
  -InputRoot 'data\agent-handoff\<explicit-subroot>'
```

For PatchDrop evidence, use an explicit `__patch_drop__\<subroot>`. Do not pass
either allowlist root itself. Never choose a path by newest timestamp.

Retain stdout `manifestSha256` outside the trace. Capture v1 is a non-atomic
recorded row set: `captureSnapshotAtomicity=unproven`.

Defaults are 512 files, 64 MiB total input, 2 MiB per parsed JSON file,
120 seconds, and 1 MiB output. Reduce them for narrower probes.

## Recheck

Require exactly one explicit trace and the original externally retained SHA:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1 `
  -Root . `
  -TraceDirectory 'data\agent-handoff\artifact-trace\<traceId>' `
  -ExpectedManifestSha256 <externally-retained-lowercase-sha256> `
  -VerificationId <lowercase-slug>
```

Do not infer the expected SHA from the trace being checked. Without that
out-of-band anchor, a self-consistent rewrite of manifest, sidecar, and ready
cannot be distinguished; return `HOLD`. Zero or multiple trace candidates are
also `HOLD`, never newest-timestamp selection.

## Interpret the Result

Require:

- `schemaVersion=awx.artifact_trace_manifest.v1`;
- `overallVerdict=INVENTORY_ONLY`;
- `mutationAllowed=false`;
- `deleteAuthorized=false`;
- `runtimeLineageVerdict=HOLD`; and
- `desktopFinalProof=evidence_needed`.

Keep `artifactState`, `freshnessState`, and `proofStatus` independent.
`structurally_bound` means only the named `proofScope`; consult every
`notProofOf` value before writing a conclusion.

- Tombstones are `cleanup_only`.
- Bound MacSrc completion records are at most `source_patch`.
- Complete producer v3 bundles are at most `patch_handoff`.
- Unknown diagnostics are `proofScope=none`.
- A ready marker alone is invalid.

Verify `manifest.sha256`, then require `.ready`. The helper creates `.ready`
last; a directory without it is an incomplete publication.

For verification packets, preserve the machine verdict:

- `INVALID`: trust anchor, envelope, or manifest contract is invalid;
- `INDETERMINATE`: two full-content scans or the envelope were unstable;
- `CHANGED`: content, recorded timestamp, missing, or added difference exists;
- `UNCHANGED`: two current scans match the capture v1 recorded row set only.

Never use `UNCHANGED` to update `artifactState`, `freshnessState`, or
`proofStatus`. Preserve `allowedClaims`, `notProofOf`,
`captureSnapshotAtomicity=unproven`, `HOLD`, and `evidence_needed`.

## Failure Policy

Fail closed on non-allowlisted/reparse/changing input, budget or timeout excess,
secret-like output, invalid binding, collision, or publication failure. Emit
one reason code only; never dump the rejected file.

Invalid artifact bindings remain inventory rows with
`trace-binding-invalid`; infrastructure or safety failures publish no manifest.

The verifier streams bounded hashes, durably stages three files, rechecks the
envelope, and renames once. It never mutates the input or source trace.

## Ownership and Removal

- owner: repo-local evidence/tooling maintainers;
- input mutation surface: none;
- output mutation surface: one new capture or verification directory only;
- secret policy: count/reason only, never raw values or bodies;
- rollback: remove this skill, its root contract test, and routing references;
  do not silently delete manifests already handed off;
- reuse rationale: classification is new, while cleanup, terminal adjudication,
  PatchDrop consumption, and skill validation remain with existing owners;
- falsifying test: any fixture that leaks raw content, upgrades a tombstone or
  producer bundle to runtime/Desktop proof, follows a reparse point, mutates an
  input, accepts a broken or fully rebound hash binding without an external
  anchor, publishes incomplete final output, or treats `UNCHANGED` as success
  proof invalidates this skill.

The optional Korean harness is `agent-prompts/agents/demo1_artifact_trace_integrity_harness/system_ko.md`.
It grants no extra authority; same-request approval cannot authorize cleanup.

Run both repository contracts:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_artifact_trace_curator_contract_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1 -Group All
```
