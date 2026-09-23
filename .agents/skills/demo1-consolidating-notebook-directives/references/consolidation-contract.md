# Notebook Directive Consolidation Contract

Use these four immutable shapes when a bounded Notebook lineage must become one
Desktop-owned program. Keep observed records to paths, hashes, counts, reason
codes, and categorical evidence; never place raw provider or response bodies in
an inventory, ledger, program, or retirement record.

## Contents

- Candidate Discovery
- False-Positive Exclusions
- DirectiveInventory
- RequirementLedger
- CanonicalProgramDirective
- RetirementManifest
- Conflict Resolution
- Work-Unit Execution
- Retirement Protocol
- Owner Commands

## Candidate Discovery

Search only repository-contained leaf files in these bounded forms:

- direct-child standalone `.md` or `.json` under `data/agent-handoff/notebook`;
- standalone `*directive*.md` under `__patch_drop__/notebook`; and
- top-level `agent-prompts/*source_directive*.md`.

An extra root requires an explicit user request and current proof that it is
inside the repository. Record `generatedAtUtc`, root, path, uppercase SHA-256,
bytes, Git tracking, and Notebook provenance before reconciling its text.

## False-Positive Exclusions

Exclude `source-directive-canary-v*` directories in full,
`agent-prompts/agents/**`, `agent-prompts/out/**`, `*.report.md`,
`*.verify.log`, `*.manifest.json`, `*.sha256.txt`, and PatchDrop v3 sidecars
(`*-v3.report.md`, `*-v3.verify.log`, `*-v3.sha256.txt`,
`*-v3.manifest.json`). Also exclude tracked reusable prompts, directories,
reparse points, and all paths outside the repository. Do not count an excluded
path as a candidate merely because its name contains `directive`.

## DirectiveInventory

```yaml
schemaVersion: demo1.notebook-directive-inventory.v1
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
candidateRoots:
  - data/agent-handoff/notebook
  - __patch_drop__/notebook
  - agent-prompts
candidates:
  - path: data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    sha256: 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    bytes: 12605
    gitTracking: untracked
    provenance: notebook
    format: json
    directiveIds:
      - G-20260802-RAG-TAIL-WEB-01
    targetFiles: []
    inclusionReason: standalone-notebook-directive
excluded:
  - path: data/agent-handoff/notebook/source-directive-canary-v1/desktop-ack.template.json
    reason: sealed-canary
```

Every candidate has `path`, `sha256`, `bytes`, `gitTracking`, `provenance`,
`format`, `directiveIds`, `targetFiles`, and `inclusionReason`. Use
`gitTracking: tracked | untracked | ignored | unknown`, `provenance: notebook |
notebook-patchdrop-intent | desktop | patchdrop | prompt | evidence_needed`,
and `format: json | markdown | yaml | text | unknown`. Use
`inclusionReason: standalone-notebook-directive | target-specific-red-directive
| approved-canonical-input`; otherwise exclude it with a categorical reason.

Valid exclusion reasons are `sealed-canary`, `reusable-prompt`,
`patchdrop-sidecar`, `report-or-verification`, `directory`, `reparse-risk`,
`outside-repo`, `provenance-uncertain`, and `unsupported`. Resolve each path
without traversing a reparse point, verify repository containment, and freeze
the inventory before reconciliation.

## RequirementLedger

```yaml
schemaVersion: demo1.notebook-requirement-ledger.v1
requirements:
  - requirementId: ND-RAG-WEB-001
    normalizedRequirement: Preserve the three-query RAG tail and counter-evidence contract.
    sourcePaths:
      - data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    sourceHashes:
      - 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    category: verification
    status: evidence_needed
    liveEvidence: []
    targetFiles: []
    redTests: []
    greenTests: []
    conflictsWith: []
    decision: hold
```

Normalize one requirement per ledger row. Use `category: source | test |
verification | runtime | retirement | safety | non-goal`, `status: pending |
already_present | unimplemented | conflict | stale | not_applicable |
evidence_needed | verified | held | rejected`, and `decision: retain | apply |
implement | hold | reject`. Preserve source paths and hashes and attach current
C-root evidence; never make a past Notebook PASS claim sufficient evidence.

## CanonicalProgramDirective

```yaml
contractVersion: demo1.notebook-directive-consolidation.v1
programId: awx-desktop-notebook-consolidated-source-20260806
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
sourceOwner: desktop
activeSourceSets:
  - main/java
  - main/resources
workUnits: []
desktopFinalProof: evidence_needed
evidence_needed: run W0 fixture RED
```

Publish exactly one program at its canonical path and report its external
SHA-256. Each work unit names its `workUnitId`, requirement IDs, causal
boundary, target and excluded files, owner, RED tests, GREEN tests, preimages,
verification commands, rollback, and `status: pending | no_patch_needed | red
| green | hold`. `desktopFinalProof` is `evidence_needed | verified | pass |
hold`; delivery or a rendered response alone cannot make it `pass`.

## Conflict Resolution

Current C-root source and tests decide factual conflicts. For safety, redaction,
or authority constraints, keep the stricter compatible rule. If that does not
resolve a conflict, keep its ledger row `hold`; never select a majority winner
or make a silent union of incompatible directives.

For the pressure shape where raw-body exposure conflicts with bounded redaction,
report the conflict record as `decision=HOLD`, `rawBodyExposure=false`, and
`currentCEvidenceWins=true`: C-root evidence selects the bounded rule for the
future canonical program, while `HOLD` prevents a silent union, retirement, or
raw-body publication from the conflicting input.

## Work-Unit Execution

Freeze the inventory before normalizing requirements, then publish the canonical
program before execution. Group only requirements with the same causal boundary.
Each work unit requires its own existing Desktop preflight, source-owner lease,
RED, GREEN, focused verification, and rollback. This skill does not invent a
broad source lease, lease implementation, or replacement PatchDrop workflow.

## RetirementManifest

```yaml
schemaVersion: demo1.notebook-directive-retirement.v1
deleteAuthorized: true
canonicalDirectivePath: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
canonicalDirectiveSha256Evidence: external-final-output
allRequiredWorkUnitsGreen: false
items: []
status: pending
```

Each item records exact `path`, preimage SHA-256, Git tracking, coverage
decision, hold reason, and deletion result. Use `eligibility: eligible |
excluded | hold`, `deletionResult: not_run | removed | hash_changed | missing`,
and `status: pending | hold | eligible | retired | failed`.

## Retirement Protocol

1. Publish the canonical directive and calculate its external SHA-256.
2. Disclose its path and hash before retirement begins.
3. Confirm every required work unit is GREEN.
4. Re-hash every candidate immediately before deletion.
5. Remove only exact eligible leaf files.
6. Verify old paths are absent and the canonical path remains.

Changed hashes, sealed Canaries, tracked reusable prompts, and incomplete
coverage remain `hold`. Never delete an excluded item or a changed preimage.
When a work unit lacks a valid RED, report the sole next proof as
`missing-valid-RED`; do not rename it to a multi-step implementation plan.

## Owner Commands

Run the existing Desktop collision preflight, then use
`demo1-source-edit-three-way-preflight` before any source write. Keep producer
bundles under the existing PatchDrop janitor/orchestrator and run
`demo1-skill-family-postprocessor` validation before treating this skill as
complete. These owners are routing boundaries, not replacement scripts.
