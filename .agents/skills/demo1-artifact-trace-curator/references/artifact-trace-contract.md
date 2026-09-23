# Artifact Trace Contract

## Purpose

`new_artifact_trace_manifest.ps1` creates an inventory of leftover task
artifacts without turning persistence into success evidence. It is a reader and
publisher of derived metadata, not a janitor or adjudicator.

## Input

Required:

- `Root`: proven repository root;
- `TraceId`: lowercase slug matching `^[a-z0-9][a-z0-9-]{0,63}$`;
- `InputRoot`: relative strict descendant of `data/agent-handoff/` or
  `__patch_drop__/`.

Default limits:

| Parameter | Default | Meaning |
| --- | ---: | --- |
| `MaxFiles` | 512 | Maximum inventoried files |
| `MaxTotalMiB` | 64 | Maximum total input bytes |
| `MaxJsonMiB` | 2 | Maximum bytes for one parsed JSON file |
| `TimeoutSeconds` | 120 | Total wall-clock budget |
| `MaxOutputMiB` | 1 | Maximum serialized manifest bytes |

Zero is accepted only to support deterministic fail-closed tests.

The input cannot be either allowlist root itself, the output root, a parent of
the selected output, an absolute path, a missing directory, or any path
containing a reparse point.

## Output

Successful output:

```text
data/agent-handoff/artifact-trace/<traceId>/
  manifest.json
  manifest.sha256
  .ready
```

`manifest.json` uses:

```text
schemaVersion: awx.artifact_trace_manifest.v1
overallVerdict: INVENTORY_ONLY
mutationAllowed: false
deleteAuthorized: false
runtimeLineageVerdict: HOLD
desktopFinalProof: evidence_needed
```

The sidecar is `<lowercase-sha256><two spaces>manifest.json`. The ready marker
is published after the manifest and sidecar. Its absence means incomplete
publication.

## Artifact row

Every row contains:

- repository-relative `path`, `sizeBytes`, `sha256`, `lastWriteTimeUtc`;
- `artifactRole`, `artifactState`, `evidenceRole`;
- `proofStatus`, `proofScope`;
- `sourceVerdict.schemaVersion`, `.state`, `.ownerHash`, `.recordSha256`;
- `allowedClaims`, `notProofOf`;
- `freshnessState`, `supersedes`, `retentionClass`;
- `deleteAuthorized=false`;
- `failureClassifications`.

Allowed proof status values:

- `none`: metadata presence only;
- `self_reported`: recognized standalone record, not sibling-bound proof;
- `structurally_bound`: required hashes and sibling fields match;
- `invalid`: recognized evidence is incomplete or inconsistent.

Allowed proof scopes:

- `none`;
- `cleanup_only`;
- `source_patch`;
- `patch_handoff`.

Runtime, deployment, approval, current-state, and Desktop-apply proof are never
granted by this manifest.

Freshness values are `current`, `expired`, `superseded`, or `unknown`.
Freshness uses explicit record fields, not file modification time. Expired or
superseded records may be `prune_candidate`, but this never authorizes deletion.
`supersedes` values are hash-only.

## Known evidence

### Lease cleanup tombstone

`awx.macsrc_smb_patch_lease_cleanup.v1` is:

- `artifactRole=tombstone`;
- `proofStatus=self_reported`;
- `proofScope=cleanup_only`;
- `retentionClass=prune_candidate`.

It proves only that a cleanup record exists. It is not proof of source patch,
PatchDrop handoff, Desktop apply, runtime, deployment, approval, or current
state.

### MacSrc completion

`awx.macsrc_smb_patch_completion.v2` is structurally bound only when exactly one
session and one verification record in the same selected input root satisfy:

- completion state is `COMPLETE`;
- run IDs match;
- the actual session SHA-256 equals `sessionSha256`;
- the actual verification SHA-256 equals `verificationEvidenceSha256`;
- verification binds the same session SHA and has nonblank command/exit 0;
- verification `targetPostimages` exactly equal completion `postimages`;
- raw secret and undeclared source change counts are zero.

The resulting scope is only `source_patch`. Missing or mismatched siblings yield
`proofStatus=invalid` and `trace-binding-invalid`.

### PatchDrop v3

A producer bundle is structurally bound only when the selected directory has
one same-prefix:

- `.patch`;
- `.report.md`;
- `.verify.log`;
- `.sha256.txt`;
- `.manifest.json`.

The manifest must declare `patchdrop-producer-v3`, `patchdrop-v3`, cumulative
true, the exact active patch, `desktopFinalProof=evidence_needed`, zero secret
pattern hits, and source isolation:

```text
guard=PASS
sourceRootKind=local-worktree
sharedSourceRoot=false
desktopCanonicalSourceRoot=false
directCanonicalSourceEdit=false
gitRootPresent=true
gitRootMatchesSourceRoot=true
gitRootHash=<64 lowercase/uppercase hex accepted>
```

The sidecar must contain unique matching SHA-256 entries for the patch, report,
verify log, and manifest. Extra entries do not increase proof scope. The maximum
scope remains `patch_handoff`; Desktop apply and runtime remain unproved. A
ready marker without this bundle is invalid.

### Unknown files

Unknown binary/text files produce hash and filesystem metadata only. Parsed but
unrecognized JSON exposes no raw object or values. Both have
`proofStatus=none`, `proofScope=none`.

## Failure classifications

| Reason | Policy |
| --- | --- |
| `trace-input-not-allowlisted` | fail closed, no manifest |
| `trace-input-missing` | fail closed, no manifest |
| `trace-reparse-risk` | fail closed, no traversal |
| `trace-input-changed` | fail closed, no unstable hash claim |
| `trace-budget-exceeded` | fail closed, delete temp output only |
| `trace-json-too-large` | fail closed, do not parse |
| `trace-secret-risk` | fail closed, do not publish raw value |
| `trace-output-collision` | fail closed, never overwrite |
| `trace-binding-invalid` | retain bounded invalid row |
| `trace-publication-failed` | fail closed, remove incomplete generated output |

Input artifacts are never removed, moved, renamed, quarantined, or rewritten.
Only an exact temporary/final directory under
`data/agent-handoff/artifact-trace/` may be cleaned after failed publication.

## Post-publication integrity verification

`test_artifact_trace_manifest.ps1` rechecks one explicit capture without
mutating the captured input or source trace. Required inputs are:

- `Root`;
- one repository-relative
  `data/agent-handoff/artifact-trace/<traceId>` directory;
- `ExpectedManifestSha256`, retained from the original capture stdout outside
  the trace directory; and
- one lowercase `VerificationId`.

The expected SHA is an independent trust anchor. The manifest, sidecar, and
ready marker are colocated and can otherwise be rewritten together. Deriving
the expected SHA from the trace under inspection is invalid and must be `HOLD`.

Capture schema v1 records bounded rows but does not prove a single atomic
capture instant. Every verification packet therefore contains:

```text
captureSnapshotAtomicity: unproven
```

### Envelope authentication

The source trace contains exactly three regular, non-reparse files:

```text
manifest.json
manifest.sha256
.ready
```

The actual manifest hash must match the external expected SHA, the exact
sidecar bytes, and the exact ready binding. JSON must be strict UTF-8 without
BOM/NUL/replacement characters, duplicate or case-colliding properties, loose
types, or inconsistent counts. Relative paths reject traversal, ADS colons,
reserved DOS devices, trailing dots/spaces, and case-fold collisions.

Manifest, sidecar, ready, and current input files are length-checked before
reading. SHA-256 is streamed through a fixed-size buffer; only the already
bounded envelope text is retained for parsing. Directory enumeration is also
streamed. Budget and reparse failures are safety failures and publish no
packet rather than being collapsed into `INVALID`.

Artifact `allowedClaims` must be a nonempty subset of the capture-v1 claim
vocabulary. Every artifact must deny at least Desktop apply, runtime,
deployment, approval, and current state. Proof status/scope pairs must be
coherent; arbitrary claims such as runtime success invalidate the manifest.

### Stable comparison

The verifier computes two complete current inventories containing path, size,
SHA-256, and recorded last-write UTC. Both canonical inventories and the exact
three-file envelope identity must remain equal. Otherwise the result is
`INDETERMINATE`.

Change categories are mutually exclusive:

- `content_changed`: bytes or size differ;
- `metadata_only`: bytes match and recorded last-write UTC differs;
- `missing`: expected path is absent;
- `added`: current path was not recorded.

`changedCount` means content changes only. `metadataOnlyChangedCount` is
separate and participates in verdict selection. No ACL, attribute, alternate
stream, or other broad metadata claim is made.

Change rows remain in stable order. The verifier retains the greatest prefix
whose serialized packet fits `MaxOutputMiB`, sets `changesTruncated`, and
reports the exact omitted row count. Aggregate counts are never truncated.

### Verification output

```text
data/agent-handoff/artifact-trace-verification/<verificationId>/
  verification.json
  verification.sha256
  .ready
```

All three files are durably flushed and validated in one sibling staging
directory; `.ready` is written last there. A shared-output `CreateNew` lock with
`DeleteOnClose` serializes the same verification ID across host/path aliases
without leaving a normal stale lock. An unguessable owner token protects
failure cleanup and is removed before final membership validation. Immediately
before publication, the source envelope is read again. An observed change
rewrites the staged packet as `INDETERMINATE` with
`trace-verify-envelope-changed`. The exact three-file directory is then renamed
once within the same parent. Collision never overwrites. Failure cleanup may
remove only a staging directory whose owner token still matches this run.

The packet contract is:

```text
schemaVersion: awx.artifact_trace_verification.v1
overallVerdict: VERIFICATION_ONLY
integrityVerdict: INVALID | INDETERMINATE | CHANGED | UNCHANGED
mutationAllowed: false
deleteAuthorized: false
runtimeLineageVerdict: HOLD
desktopFinalProof: evidence_needed
```

`UNCHANGED` grants only `bounded_recorded_row_set_match_observed`. It does not
change `artifactState`, `freshnessState`, or `proofStatus`. Preserve every
`allowedClaims` and `notProofOf`; capture atomicity, freshness, patch success,
approval, Desktop apply, deployment, provider/runtime lineage, and current
state remain unproved.

Additional verification reasons include:

- `trace-verify-expected-manifest-sha-mismatch`;
- `trace-verify-envelope-invalid`;
- `trace-verify-manifest-sha-mismatch`;
- `trace-verify-ready-binding-invalid`;
- `trace-verify-manifest-contract-invalid`;
- `trace-verify-input-unstable`;
- `trace-verify-envelope-changed`;
- `trace-verify-budget-exceeded`;
- `trace-verify-secret-risk`;
- `trace-verify-output-collision`; and
- `trace-verify-publication-failed`.

No verdict, retention label, or same-request user approval expands this skill
into deletion, movement, quarantine, apply, unlock, repair, or janitor work.

## Markdown harness contract

`agent-prompts/agents/demo1_artifact_trace_integrity_harness/system_ko.md`
accepts only `operation=capture|recheck` with one proven root and one explicit
path/ID set. Recheck additionally requires the externally retained manifest
SHA. Missing or ambiguous values are `HOLD`; the harness never chooses newest
by time and never gains cleanup authority from same-request approval.

The harness reports `Observation`, `Integrity`, `Proof Limits`, and `Next`.
It must preserve the machine verdict, `captureSnapshotAtomicity=unproven`,
`mutationAllowed=false`, `deleteAuthorized=false`, runtime `HOLD`, and Desktop
`evidence_needed`.

## Rollback

Remove the verifier script, verifier contract test, harness directory, prompt
manifest entry/generated prompt, and recheck additions to this skill and
reference. Restore the prior capture-only metadata. Do not delete historical
capture or verification packets during rollback; they remain evidence with
their original bounded meaning.
