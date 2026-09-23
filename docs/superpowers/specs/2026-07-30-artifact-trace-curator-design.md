# Artifact trace curator design

Date: 2026-07-30
Status: approved design, awaiting written-spec review
Workspace: `\\desktop-m5nov6k\MacSrc`

## 1. Objective

Create one repo-local skill, `demo1-artifact-trace-curator`, that inventories
task traces under `data/agent-handoff/**` and `__patch_drop__/**` without
turning file existence into a success, approval, deployment, runtime, or
current-state claim.

The skill must preserve useful traces while making their meaning explicit. It
must distinguish the artifact's lifecycle from the claim it can support and
from the authority needed to start another mutation.

## 2. Existing owners and non-duplication

The new skill is an inventory and semantic-normalization boundary. It is not a
second verifier, patch janitor, or source editor.

- `demo1-macsrc-smb-direct-patch` owns guarded source mutation and emits
  session, verification, completion, abort, failure, and lease-cleanup files.
- `demo1-macsrc-patch-postprocessor` owns terminal patch adjudication. Its
  `COMPLETE`, `HOLD`, and `ROLLBACK_REQUIRED` decisions remain authoritative
  for that workflow.
- PatchDrop janitor scripts own bundle movement, apply, rejection,
  supersession, and queue cleanup.
- `demo1-skill-family-postprocessor` owns repo-local skill validation and
  compact skill-family completion evidence.

The curator may read outputs from these owners and copy their exact machine
tokens with source hashes. It must not synthesize an equivalent verdict or
replace the owning tool.

## 3. Scope

### 3.1 Allowed input roots

Accept only an explicit repo-relative input root contained by one of:

- `data/agent-handoff/**`
- `__patch_drop__/**`

Reject the repository root, application source, build outputs, arbitrary logs,
OneDrive paths, external shares, and paths containing reparse-point traversal.
An invocation processes one explicit run directory or one explicit PatchDrop
bundle family. It does not recursively inventory both top-level roots by
default.

### 3.2 Allowed output root

Write derived evidence only under:

```text
data/agent-handoff/artifact-trace/<traceId>/
```

The output set is:

- `manifest.json`
- `manifest.sha256`
- `.ready`, published last

Temporary output uses a sibling name, is flushed and hashed, and is renamed
before `.ready` is created.

### 3.3 Prohibited behavior

The curator must not:

- edit application source, skill source, PatchDrop payloads, or input traces;
- delete, move, quarantine, promote, apply, restore, or supersede artifacts;
- authorize another mutation;
- infer success from a filename, timestamp, `.ready` marker, or exit code;
- emit raw prompts, raw queries, raw response bodies, credentials, headers,
  cookies, environment dumps, or raw diagnostic logs;
- claim Desktop final proof, deployment, runtime success, provider lineage,
  approval, or current state without an exact owner-produced record that is
  valid for that scope.

## 4. New skill shape

Create:

```text
.agents/skills/demo1-artifact-trace-curator/
  SKILL.md
  agents/openai.yaml
  references/artifact-trace-contract.md
  scripts/new_artifact_trace_manifest.ps1
```

Create the behavior contract test at:

```text
scripts/demo1_artifact_trace_curator_contract_tests.ps1
```

Update these existing skill entrypoints only to reference the curator at the
appropriate boundary:

- `.agents/skills/demo1-macsrc-smb-direct-patch/SKILL.md`
- `.agents/skills/demo1-macsrc-patch-postprocessor/SKILL.md`
- `.agents/skills/demo1-skill-family-postprocessor/SKILL.md`

Do not create a separate trace reader or retention-janitor skill. Retention
judgment belongs in the curator manifest; mechanical deletion remains with the
existing lease and PatchDrop janitors.

## 5. Manifest contract

Use schema `awx.artifact_trace_manifest.v1`.

### 5.1 Top-level fields

```yaml
schemaVersion: awx.artifact_trace_manifest.v1
traceId: stable_caller_supplied_id
generatedAtUtc: ISO-8601 UTC
canonicalRoot: proven MacSrc root
inputRoot: repo-relative explicit input root
inputRootKind: agent_handoff | patchdrop
inventorySha256: sha256
artifactCount: 0
rawContentStored: false
mutationAllowed: false
deleteAuthorized: false
overallVerdict: INVENTORY_ONLY
runtimeLineageVerdict: HOLD
desktopFinalProof: evidence_needed
records: []
failureClasses: []
nextAction: STOP | REVIEW_INVALID_RECORD | RUN_OWNING_POSTPROCESSOR
```

`overallVerdict=INVENTORY_ONLY` is invariant. The manifest can describe an
owner's terminal verdict but can never become that verdict.

### 5.2 Per-artifact fields

Each record contains:

```yaml
artifactId: sha256_of_normalized_relative_path_and_content_hash
relativePath: repo-relative path
contentSha256: sha256 | null
byteCount: nonnegative integer
artifactRole: session | verification | completion | abort | failure |
  tombstone | patch | report | verify_log | checksum | bundle_manifest |
  ready_marker | diagnostic | unknown
artifactState: present | active | terminal | superseded | expired |
  quarantined | tombstone
evidenceRole: authoritative_input | supporting | derived_summary |
  diagnostic | temporary | unknown
proofStatus: none | structurally_bound | adjudicated | invalid
proofScope: cleanup_only | source_patch | test_only | patch_handoff |
  desktop_final | runtime_lineage | none
sourceVerdict: exact_machine_token | null
sourceVerdictOwner: skill_or_script_name | null
sourceVerdictRecordSha256: sha256 | null
allowedClaims: []
notProofOf: []
freshnessState: current | expired | unknown
supersedesArtifactId: sha256 | null
retentionClass: keep | prune_candidate | policy_owned_elsewhere
deleteAuthorized: false
reasonCodes: []
```

The dimensions are independent:

- `artifactState` describes the file lifecycle.
- `proofStatus` describes validation of the record for one bounded scope.
- `proofScope` names that scope.
- `allowedClaims` lists only literal claims supported by the owning evidence.
- `notProofOf` blocks predictable over-interpretation.

`proofStatus=adjudicated` is allowed only when an owner-produced adjudication
record is present, its schema is recognized, and its content SHA is stored in
`sourceVerdictRecordSha256`. Otherwise use `structurally_bound`, `none`, or
`invalid`.

## 6. Required classification rules

### 6.1 Lease cleanup tombstone

For `awx.macsrc_smb_patch_lease_cleanup.v1`:

```text
artifactState=tombstone
evidenceRole=authoritative_input
proofStatus=structurally_bound
proofScope=cleanup_only
allowedClaims=[stale_lock_cleanup_recorded]
notProofOf=[patch_success,runtime_success,deployment,approval,current_state]
deleteAuthorized=false
```

The tombstone proves only that the cleanup tool recorded its action. It does
not prove that source was correct, verified, deployed, or currently unchanged.

### 6.2 Guard terminal files

A completion or abort file is `structurally_bound` only when its run ID and
session SHA match the sibling session. Completion also requires the sibling
verification hash and postimage map to match. A missing or mismatched sibling
record yields `proofStatus=invalid`; file existence never yields success.

The curator copies `COMPLETE` or `ABORTED` into `sourceVerdict` when present but
keeps `overallVerdict=INVENTORY_ONLY` and `mutationAllowed=false`.

### 6.3 PatchDrop bundles

Reuse PatchDrop v3 rules without moving files. A `.ready` marker without the
manifest-pinned patch, report, verify log, and valid SHA sidecar is `invalid`.
A structurally complete producer bundle proves `patch_handoff` only; it is not
proof of Desktop apply, Gradle success, deployment, or runtime behavior.

Applied, rejected, orphan, and superseded locations describe lifecycle. They
do not change proof scope unless an owner-produced result file supplies the
corresponding exact machine token.

### 6.4 Unknown and diagnostic files

Unknown schemas and raw diagnostics default to:

```text
artifactState=present
evidenceRole=unknown | diagnostic
proofStatus=none
proofScope=none
allowedClaims=[]
notProofOf=[success,approval,deployment,runtime_success,current_state]
```

Do not inspect or include raw diagnostic content. Record count, size, SHA, and
redacted reason codes only.

### 6.5 Freshness and retention

Freshness is not correctness. An expired record can remain structurally bound
to its historical scope, while a current record can still be invalid.

The curator may emit `retentionClass=prune_candidate` for an expired,
superseded, or explicitly temporary record. It always emits
`deleteAuthorized=false`; another owner must decide and perform deletion.

## 7. Data flow

1. Resolve the canonical root and explicit relative input root.
2. Reject paths outside the two allowlisted trace roots or through reparse
   points.
3. Inventory files in stable relative-path order with bounded count and byte
   limits.
4. Scan secrets count-only and compute SHA-256 without returning raw content.
5. Parse only allowlisted JSON schemas. Treat parse failures as records, not
   raw error dumps.
6. Resolve sibling bindings for known guard and PatchDrop contracts.
7. Produce orthogonal lifecycle, evidence, proof, freshness, and retention
   fields.
8. Write `manifest.json`, verify its SHA sidecar, and publish `.ready` last.

## 8. Bounds and failure policy

- Default maximum: 512 files and 64 MiB total input bytes.
- Maximum individual parsed JSON: 2 MiB.
- Timeout: 120 seconds.
- Output manifest maximum: 1 MiB.
- Fail closed for root escape, reparse traversal, output collision, secret hit
  in a would-be JSON output, or publication/hash mismatch.
- Fail soft per input record for unknown schema, malformed JSON, missing
  sibling, stale timestamp, or unsupported artifact type.
- Use stable failure classes such as `trace-root-invalid`,
  `trace-budget-exceeded`, `trace-record-invalid`, `trace-binding-mismatch`,
  `trace-secret-risk`, and `trace-publication-failed`.

## 9. Skill trigger and non-trigger

Trigger when task completion, PatchDrop, MacSrc sessions, tombstones, reports,
verification files, or agent-handoff history exist and a consumer could mistake
their presence for success or current truth.

Do not trigger for application runtime logging, TraceStore instrumentation,
source debugging, archive restore, or generic filesystem cleanup.

## 10. Verification design

The contract test uses temporary fixtures under the two allowed roots and
exercises the real PowerShell script.

Required cases:

1. A cleanup tombstone is classified `cleanup_only` and explicitly not proof
   of patch or runtime success.
2. A completion missing its verification sibling is `invalid`, not success.
3. A hash-bound completion is `structurally_bound` for `source_patch` while the
   top-level verdict stays `INVENTORY_ONLY`.
4. A `.ready` marker with an incomplete PatchDrop v3 bundle is `invalid`.
5. A complete producer bundle is limited to `patch_handoff` and does not claim
   Desktop apply.
6. An unknown diagnostic file stores no raw content and supports no claim.
7. Expiration changes `freshnessState` or `retentionClass`, not proof status.
8. A reparse escape, oversized inventory, raw secret risk, or output collision
   fails closed.
9. `.ready` is published after the manifest and SHA sidecar.
10. All fixtures are removed and no application-source path is mutated.

Run `quick_validate.py` on the new and edited skills, then run
`demo1-skill-family-postprocessor` with `-DiscoverPrefix demo1- -SummaryJson`.
The final evidence must report count-only secret results, exact target hashes,
and zero leftover fixtures.

## 11. Rollout and rollback

Roll out as an optional repo-local skill. Do not make existing guard completion
depend on the curator. Consumers may adopt the manifest incrementally.

Rollback removes the new skill directory and its root contract test, then
removes the three cross-references from existing skills. Preserve previously
generated manifests as historical derived evidence and mark them unsupported;
do not reinterpret or silently delete them.

## 12. Success criteria

The implementation is complete when:

- the new skill and metadata validate;
- every required real-script contract test passes after a witnessed RED;
- known terminal and PatchDrop artifacts are hash-bound to their actual scope;
- tombstones and unknown files cannot be reported as success, approval,
  deployment, runtime lineage, or current state;
- the curator never deletes inputs or authorizes mutation;
- existing MacSrc direct-patch and skill-family tests remain green;
- all changed-file postimages and compact verification evidence are retained.
