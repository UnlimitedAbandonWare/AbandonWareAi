# Artifact Trace Integrity Verifier and Markdown Harness Design

## Status

- design status: approved in conversation on 2026-07-30;
- request class: `prompt_skill_tooling_only`;
- application source mutation: forbidden;
- source roots covered: explicit descendants of `data/agent-handoff/` or
  `__patch_drop__/` already captured by `demo1-artifact-trace-curator`;
- verification output root:
  `data/agent-handoff/artifact-trace-verification/<verificationId>/`;
- Desktop runtime lineage: `HOLD`;
- Desktop final proof: `evidence_needed`.

## Problem

The existing curator authenticates and classifies artifacts while it creates
`awx.artifact_trace_manifest.v1`. It does not later prove whether:

- the published manifest, sidecar, or ready marker was altered;
- a captured input file was changed, removed, or added;
- only filesystem metadata changed while bytes stayed equal; or
- the source changed during a verification scan.

File persistence must not be confused with integrity, freshness, patch success,
approval, Desktop apply, deployment, runtime lineage, or current state.

## Goals

1. Recheck one explicit published trace against the current captured input.
2. Distinguish `UNCHANGED`, `CHANGED`, `INDETERMINATE`, and `INVALID`.
3. Leave a compact, separately checksummed, ready-last verification packet.
4. Add a Korean Markdown harness that routes capture and recheck through
   `$demo1-artifact-trace-curator` without inventing stronger proof.
5. Preserve input artifacts and the original trace as read-only evidence.
6. Keep the implementation deterministic, bounded, redacted, and removable.

## Non-Goals

- deleting, moving, quarantining, superseding, or repairing an artifact;
- automatically cleaning a stale lock;
- adjudicating a MacSrc terminal patch;
- applying or promoting a PatchDrop bundle;
- proving Desktop apply, build, deployment, provider response, or runtime
  lineage;
- selecting the newest trace, bundle, or verification by timestamp;
- modifying application source, public APIs, databases, credentials, or
  environment variables.

Existing MacSrc guards, patch postprocessors, PatchDrop janitors, Desktop
verification, and skill-family validators retain their current ownership.

## Considered Approaches

### A. Separate verifier and Markdown harness

Add one verifier beside the current generator and one prompt harness. This keeps
capture and recheck independently testable, limits regression risk in the
existing 645-line generator, and makes rollback exact.

### B. Add Capture and Verify modes to the generator

This reduces the number of scripts but mixes publication and subsequent
integrity responsibilities. Parameter and failure-state complexity would grow
inside an already large file.

### C. Perform hashes directly in the Markdown harness

This is shorter but loses deterministic containment checks, stable reason codes,
atomic evidence publication, and reusable contract tests.

Decision: implement approach A.

## Components

### Existing capture component

Keep
`.agents/skills/demo1-artifact-trace-curator/scripts/new_artifact_trace_manifest.ps1`
behavior-compatible. Only shared helpers may be factored if RED/GREEN evidence
shows no contract change; duplication is preferable to a broad refactor in this
pass.

The capture v1 manifest is a bounded recorded row set, not an atomic filesystem
snapshot. Its current enumerating and hashing phases are not a two-pass snapshot
protocol. Therefore the verifier and harness must expose
`captureSnapshotAtomicity=unproven`, and no result may claim that all rows existed
simultaneously at capture time. The capture command's stdout
`manifestSha256` must be retained outside the trace directory by the caller; it
is the independent trust anchor required by a later recheck.

### New integrity verifier

Create:

`.agents/skills/demo1-artifact-trace-curator/scripts/test_artifact_trace_manifest.ps1`

Required parameters:

- `Root`: proven repository root;
- `TraceDirectory`: repository-relative
  `data/agent-handoff/artifact-trace/<traceId>`;
- `ExpectedManifestSha256`: the exact lowercase SHA-256 retained from the
  original capture result outside the trace directory;
- `VerificationId`: lowercase slug.

Default bounds:

| Parameter | Default |
| --- | ---: |
| `MaxFiles` | 512 |
| `MaxTotalMiB` | 64 |
| `MaxJsonMiB` | 2 |
| `TimeoutSeconds` | 120 |
| `MaxOutputMiB` | 1 |

The verifier streams enumeration and SHA-256 through fixed buffers. It checks
file lengths before reading, retains bytes only for bounded envelope text, and
reads only the original trace envelope and its declared input root. It writes
only a new verification output directory.

### New Markdown harness

Create:

- `agent-prompts/agents/demo1_artifact_trace_integrity_harness/system_ko.md`;
- `agent-prompts/agents/demo1_artifact_trace_integrity_harness/meta.yaml`;
- one matching registration in `agent-prompts/prompts.manifest.yaml`.

The harness invokes `$demo1-artifact-trace-curator` first, then selects exactly
one operation:

- capture: call `new_artifact_trace_manifest.ps1` with an explicit input root;
- recheck: call `test_artifact_trace_manifest.ps1` with an explicit trace
  directory and explicit expected manifest SHA-256.

It never selects an artifact by timestamp, performs cleanup, or upgrades a
verdict beyond the machine packet.

### Skill and contract documentation

Update:

- `.agents/skills/demo1-artifact-trace-curator/SKILL.md`;
- `.agents/skills/demo1-artifact-trace-curator/agents/openai.yaml` only if its
  capture-only wording becomes stale;
- `.agents/skills/demo1-artifact-trace-curator/references/artifact-trace-contract.md`.

The skill remains one capability family. Do not create a second global skill
for verification.

### Contract tests

Create:

`scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1`

Keep the existing capture contract test unchanged except for shared static
checks that are impossible to isolate.

## Verification Algorithm

### 1. Preflight

1. Resolve `Root` without changing Git trust.
2. Require `TraceDirectory` to be a strict descendant of
   `data/agent-handoff/artifact-trace/`.
3. Require `VerificationId` to match
   `^[a-z0-9][a-z0-9-]{0,63}$`.
4. Require `ExpectedManifestSha256` to match exactly `^[a-f0-9]{64}$`.
5. Reject reparse points from the root through every inspected file.
6. Reject Windows-ambiguous relative paths: ADS colons, empty/dot segments,
   reserved DOS device names, trailing dots/spaces, and ordinal-ignore-case
   collisions. Use ordinal stable ordering.
7. Reject an existing final verification output directory.
8. Reject the output root as a source trace or captured input.

Preflight safety failures emit a compact reason to stderr and publish no
verification packet.

### 2. Authenticate the trace envelope

Require exactly:

- `manifest.json`;
- `manifest.sha256`;
- `.ready`.

Additional files in the original trace directory make the envelope `INVALID`.

Verify:

- actual manifest SHA-256 equals the independent
  `ExpectedManifestSha256`; a self-consistent rewrite of all colocated trace
  files must therefore fail;
- actual manifest SHA-256 equals `manifest.sha256`;
- ready marker schema and manifest SHA bind to the same manifest;
- sidecar and ready marker use exact UTF-8/no-BOM, no-NUL, exact-line grammars;
- JSON is strict UTF-8 with bounded depth/property counts, exact JSON types,
  and no duplicate keys including case variants;
- manifest schema is `awx.artifact_trace_manifest.v1`;
- top-level invariants remain `INVENTORY_ONLY`, false mutation/deletion,
  `HOLD` runtime lineage, and `evidence_needed` Desktop final proof;
- `inputRoot` is still an allowlisted strict descendant;
- artifact paths are unique, normalized, contained by `inputRoot`, and free of
  traversal;
- hashes, sizes, timestamps, proof fields, `traceId`, `inputRootHash`, inventory
  counts/bytes, and artifact row totals have valid mutually consistent bounded
  forms;
- `allowedClaims` is nonempty and capture-v1 allowlisted, every row denies at
  least Desktop apply/runtime/deployment/approval/current state, and proof
  status/scope pairs are coherent.

A stale or broken sidecar, ready marker, schema, path, or invariant produces an
`INVALID` packet without parsing or echoing untrusted raw content.

### 3. Recheck the captured input

Enumerate the current input root twice with streaming filesystem enumeration in
stable ordinal repository-relative path order. Each pass acquires read-only
snapshots, computes streaming SHA-256 after a pre-read length bound, and
records path, size, SHA-256, and last-write UTC. `UNCHANGED` or `CHANGED` is
allowed only if both complete content inventories are byte-for-byte equal and
all reparse/containment checks remain valid. Otherwise classify
`INDETERMINATE`. Compare the second stable inventory with manifest rows:

- same path, size, SHA-256, and last-write UTC: unchanged;
- same path with different bytes: content changed;
- same path and bytes with different last-write UTC: metadata-only changed;
- manifest path absent now: missing;
- current path absent from manifest: added.

The verifier records counts and bounded rows containing path, expected hash,
current hash, and change kind. It never includes file content.

The four expected-side categories are mutually exclusive: unchanged, content
changed, metadata-only changed, and missing. The current-side equation replaces
missing with added. `changedCount` means content-changed only; verdict selection
must also include `metadataOnlyChangedCount`. The metadata claim is deliberately
narrow: it covers recorded last-write UTC only, not ACLs, attributes, ADS, or
other filesystem metadata.

Before and immediately after both inventory passes, bind the exact envelope
membership, entry types, and SHA-256 values of all three files. Recheck the same
facts immediately before publication. Any post-authentication delta, including
sidecar-only mutation or an added entry, yields `INDETERMINATE`.

### 4. Select the integrity verdict

Use this precedence:

1. `INVALID`: source trace envelope or manifest contract is invalid.
2. `INDETERMINATE`: a safe, authenticated scan cannot reach a stable snapshot.
3. `CHANGED`: at least one changed, missing, added, or metadata-only row exists.
4. `UNCHANGED`: every declared/current row matches in two equal full-content
   inventory passes.

`UNCHANGED` means only `bounded_recorded_row_set_match_observed`: two current
bounded inventories match each other and the capture v1 recorded rows. It is
not proof of capture atomicity, freshness, patch success, approval, Desktop
apply, deployment, provider response, runtime lineage, or current business
state. It never updates artifact state, freshness state, or proof status.

## Verification Packet

Publish:

```text
data/agent-handoff/artifact-trace-verification/<verificationId>/
  verification.json
  verification.sha256
  .ready
```

Use:

```text
schemaVersion: awx.artifact_trace_verification.v1
overallVerdict: VERIFICATION_ONLY
integrityVerdict: UNCHANGED | CHANGED | INDETERMINATE | INVALID
mutationAllowed: false
deleteAuthorized: false
runtimeLineageVerdict: HOLD
desktopFinalProof: evidence_needed
```

Required fields:

- `verificationId`, `sourceTraceId`, `sourceTraceDirectory`;
- `expectedManifestSha256`, `sourceManifestSha256`,
  `sourceManifestSidecarSha256`, `sourceReadySha256`;
- `captureSnapshotAtomicity: unproven`;
- `verifiedAtUtc`, `durationMs`;
- `expectedFileCount`, `currentFileCount`;
- `unchangedCount`, `changedCount`, `missingCount`, `addedCount`,
  `metadataOnlyChangedCount`;
- `currentInventorySha256` when a stable scan exists;
- bounded `changes` rows;
- `failureClassifications`;
- `allowedClaims` and `notProofOf`.

`verification.sha256` binds the exact JSON. Change rows are added in stable
order up to the largest serialized prefix that fits `MaxOutputMiB`; aggregate
counts remain exact. A shared-output atomic `CreateNew`/`DeleteOnClose` lock
serializes the same verification ID across host/path aliases. In a sibling
temporary directory, durably flush JSON,
sidecar, and then the ready marker. An unguessable owner token binds failure
cleanup to this run. Recheck the source envelope after staging; if it changed,
rewrite the staged packet to `INDETERMINATE`. Remove the owner token, validate
the exact three-file packet, and rename it once within the same parent. No
incomplete final directory is intentionally exposed. Output collision never
overwrites, and cleanup removes only a still owner-token-matched temporary
directory.

## Failure and Redaction Policy

Fail closed with no packet for:

- non-allowlisted/reparse source or output;
- output collision;
- secret-like content in would-be output;
- file, byte, JSON, time, or output budget violation before safe
  authentication;
- unsafe path traversal;
- inability to create an atomic output.

Publish a bounded `INVALID` or `INDETERMINATE` packet only after output
containment is proven and the packet can be built without raw input content.

Reason codes include:

- `trace-verify-source-not-allowlisted`;
- `trace-verify-reparse-risk`;
- `trace-verify-envelope-invalid`;
- `trace-verify-manifest-sha-mismatch`;
- `trace-verify-expected-manifest-sha-mismatch`;
- `trace-verify-ready-binding-invalid`;
- `trace-verify-manifest-contract-invalid`;
- `trace-verify-input-unstable`;
- `trace-verify-envelope-changed`;
- `trace-verify-budget-exceeded`;
- `trace-verify-secret-risk`;
- `trace-verify-output-collision`;
- `trace-verify-publication-failed`.

Never log raw manifests, ready bodies, file bodies, credentials, queries,
headers, cookies, or full environment values.

## Markdown Harness Contract

The Korean system prompt must:

1. name `$demo1-artifact-trace-curator` before commands;
2. require a proven root and explicit path/IDs;
3. for recheck, require exactly one explicit trace directory and the externally
   retained expected manifest SHA; zero or multiple candidates are `HOLD`;
4. choose capture or recheck, never both implicitly;
5. print exact PowerShell commands;
6. report machine schema, SHA, counts, and reason codes only;
7. distinguish artifact state, freshness, proof status, and integrity verdict;
8. preserve the packet's `allowedClaims`/`notProofOf` and never update the
   artifact, freshness, or proof axes from an integrity verdict;
9. prohibit delete/move/quarantine/apply/unlock/repair even when requested in
   the same prompt; cleanup authority is limited to the current verifier's own
   identity-matched incomplete temporary output;
10. keep `runtimeLineageVerdict=HOLD` and
   `desktopFinalProof=evidence_needed`;
11. stop on ambiguity instead of selecting the newest artifact.

`meta.yaml` uses:

```yaml
id: demo1_artifact_trace_integrity_harness
lang: ko
role: system
version: "1.0.0"
tags: [demo-1, artifact-trace, integrity, tamper-check, evidence, Superpowers]
```

## Test Design

Write the verifier contract test before the verifier exists and capture the
expected RED reason. Then cover:

1. unchanged snapshot;
2. content changed;
3. timestamp-only metadata changed;
4. missing file;
5. added file;
6. multiple change types in one packet;
7. tampered manifest with stale sidecar;
8. fully rebound manifest, sidecar, and ready marker rejected by the independent
   expected manifest SHA;
9. tampered sidecar;
10. missing or altered ready marker;
11. extra source-envelope file;
12. duplicate, traversal, out-of-root, ADS, DOS-device, trailing-dot/space, or
    case-colliding manifest path;
13. continuous input mutation can never produce `UNCHANGED`; with an initially
    authenticated envelope it produces `INDETERMINATE`;
14. same-size byte mutation with restored last-write time between full content
    passes produces `INDETERMINATE`;
15. source-envelope mutation can never produce `UNCHANGED`; mutation observed
    before initial authentication produces `INVALID`, while mutation observed
    after authentication produces `INDETERMINATE`;
16. reparse traversal rejected;
17. strict UTF-8, duplicate/case-varied JSON keys, exact JSON types, BOM/NUL,
    deep structures, and extra sidecar lines rejected;
18. file/byte/JSON/time/output bounds rejected;
19. output collision and concurrent same-ID publication preserve the winner;
20. a watcher never observes an incomplete final verification directory;
21. no raw content or secret-like value in output;
22. original trace and captured input remain byte-identical unless the fixture
    intentionally changes them;
23. sidecar matches verification JSON;
24. ready marker was created last in staging and the complete packet was renamed;
25. harness contains the required skill invocation, both exact command paths,
    all four verdicts, and no input-mutation authority.

Fresh-context behavior pressure tests cover: refusing newest-trace selection,
refusing to overclaim `UNCHANGED`, and refusing requested automatic deletion
after `CHANGED`. Their tool-call transcripts, not token presence alone, decide
PASS. Compact observations are preserved in
`docs/superpowers/evidence/2026-07-30-artifact-trace-integrity-harness-pressure-tests.md`.
The prompt pack is registered in `agent-prompts/prompts.manifest.yaml`,
built with `build.py`, and checked for ID/path/UTF-8 consistency.

## Success Criteria

- verifier contract test exits 0 with no skipped required fixture;
- existing capture contract remains green;
- `quick_validate.py` accepts the updated skill;
- demo1 skill-family validation reports no new failure, trigger issue, metadata
  issue, scaffold marker, secret hit, or budget warning;
- PowerShell AST parse error count is zero;
- input mutation command scan is zero;
- application source path mutation count is zero;
- Markdown harness static contract passes;
- generated verification packet always preserves
  `VERIFICATION_ONLY`/`HOLD`/`evidence_needed`.

## Rollback

Remove:

- the verifier script;
- the verifier contract test;
- the Markdown harness directory;
- verifier-specific additions to the skill, metadata, and reference;
- this follow-on design and its implementation plan if requested.

Do not silently delete verification packets already handed off. They remain
historical, unsupported evidence after rollback and retain no mutation
authority.
