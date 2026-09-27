# Defect Intent Contract

## Required Output

`prepare_defect_intent.ps1` writes `awx.patch-intent.v1` JSON, a sibling
`.sha256` file, and a `.ready` marker published last. The default location is:

```text
data/agent-handoff/macsrc-defect-intake/<runId>/intent.json
```

The record contains:

- `schemaVersion`, `runId`, `createdAtUtc`, `stage=PREPARED`;
- `sourceRoot`, `sourceRootKind`, `defectClass`, and sorted `evidenceIds`;
- normalized `targetFiles`, `watchRoots`, and `boundaryEvidenceFiles`;
- `boundaryProofType=GradleSourceSet|CallPath` as the caller's
  explicit semantic assertion; the script hashes evidence but does not infer
  Gradle or call-graph meaning;
- target preimage and boundary evidence SHA-256 rows;
- RED/GREEN commands, `red.status=NOT_RUN`, and a redacted expected signal;
- `sourceWriteMode=UNDECIDED`, `mutationAllowed=false`;
- demand-driven Supabase mode and presence booleans only;
- `nextAction=REPRODUCE_RED` and `desktopFinalProof=evidence_needed`.

## Input Rules

- `runId`: 3-81 ASCII letters, digits, dot, underscore, or hyphen.
- `defectClass`: lower-case reason code using letters, digits, dot, or hyphen.
- All paths are relative to the explicit root and must already exist.
- Every target must be equal to or below one watch root.
- Output remains inside the root and may not overwrite an existing intent or
  sidecar.
- Output is restricted further to
  `data/agent-handoff/macsrc-defect-intake/<runId>`; caller-controlled output
  may never target an active source directory.
- Reparse traversal and secret-like input fail closed.

When the source boundary is not proven, use `-Mode Hold`. It writes
`awx.patch-intake-hold.v1` to
`data/agent-handoff/macsrc-defect-intake/<runId>/hold.json` with one failure
class and `nextAction=COLLECT_BOUNDARY_EVIDENCE`. Pass the observed candidate
as `-DefectClass` and the blocking gate as `-FailureClass`; the record keeps
them as `candidateDefectClass` and `failureClass`. It does not publish a
`PatchIntent`. A repository policy alone is not an active sourceSet proof.

## Consumer Rules

Require the `.ready` marker first. Recompute the JSON SHA-256 and compare it
with both the sidecar and marker before consumption. JSON or sidecar files
without a marker are incomplete publication, not a usable record.
Recompute target and boundary hashes immediately before opening a patch guard.
Never edit the intent to record later phases; later phases create separate,
hash-linked records.

Supabase mode `REQUIRED_READ_ONLY` records only `authPresent` and
`projectRefPresent`. Missing scope produces evidence-needed in postprocessing;
the intent never stores a project ref, access token, SQL, or query result.

## Autograder Probe Contract

`prepare_autograder_probe.ps1` consumes only bounded
`awx.autograder.evidence.v1`: run ID, exit code, failing-test count, reason
class, output SHA-256, and `mutationAllowed=false`. Raw logs or extra fields
fail closed. It emits a ready-last probe manifest and an exact dispatch order:
Positive and Negative in parallel, Neutral after both, then four bounded
deterministic transitions:

1. `Finalize` recomputes the official ten-component score, checks evidence-ID
   bindings and A-B/B-A order stability, and writes
   `awx.autograder.intake-decision.v1`.
2. `GradeSandbox` requires an APPLY decision, ready-last
   `awx.autograder.intent-spec.v1`, and a digest-pinned immutable Docker job.
   It writes `awx.autograder.grade-transition.v1`; infrastructure failures and
   a missing expected RED signal remain HOLD.
3. `PromoteIntent` requires a COMPLETE failing grade transition. It calls the
   existing intent builder, derives ready-last `awx.red-evidence.v1` from the
   bounded Docker result, and writes `awx.autograder.intent-promotion.v1`.
4. `PlanSession` consumes the derived RED record, calls the existing guarded
   session planner, and writes `awx.autograder.session-transition.v1`.

Every transition binds the exact input SHA-256 values. `Finalize` creates no
PatchIntent; `GradeSandbox` writes no source; `PromoteIntent` executes no test;
`PlanSession` writes no source and does not acquire a lease. A HOLD/REJECT
decision, missing intent spec, passing RED command, mismatched command/hash,
raw secret-like text, or non-MacSrc root prevents forward progress.
