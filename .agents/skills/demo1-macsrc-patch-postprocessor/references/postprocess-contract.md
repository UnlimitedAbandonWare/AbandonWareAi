# Patch Postprocess Contract

## Patch-Specific Tri-Query Record

The neutral reviewer writes `awx.patch-tri-query.final.v1` with:

- `subjectRunId` equal to the patch run;
- `subjectManifestSha256` equal to the SHA-256 of the fixed ready-last
  `subject.manifest.json`;
- the exact `subjectInputHashes` map for intent, session, outcome, GREEN
  verification, before/after integrity, and optional Supabase metadata;
- relative Positive, Negative, and Neutral packet files under the run's
  postprocess handoff directory;
- SHA-256 for the Positive, Negative, and Neutral packets;
- a `requestSha256` in each Positive, Negative, and Neutral packet equal to
  that role's request hash in `subject.manifest.json`;
- `forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]`;
- `reverseOrderChecked=true`, `orderStable=true|false`;
- `verdict=APPLY|HOLD|REJECT`;
- all ten official `goalScoreComponents` values in `0..1`;
- `goalScoreEvidenceIds`, with a non-empty list for every component and every
  ID equal to a key in the frozen `subjectInputHashes` map;
- `goalScore=0..100`, equal to the deterministic repository calculation;
- `mutationAllowed=false`, `secretPatternHits=0`.

Positive and Negative see the same immutable input hashes and cannot read each
other. Neutral runs only after both packets exist and may not add new facts.
Use `prepare_patch_tri_query.ps1` to create the subject manifest and staged
requests. The finalizer requires the fixed ready-last subject manifest,
verifies its SHA-256 and frozen input map, recomputes every listed request-file
hash, and requires each packet to bind its own prepared request SHA-256.
Missing, foreign, stale, or tampered manifest/request lineage is
`tri-query-invalid` and therefore `HOLD`/`RERUN_TRI_QUERY`. The finalizer also
recomputes every packet SHA and input map; formatted 64-hex strings without
real files are invalid. It calls the shared
`scripts/awx_goal_score_contract.ps1` evaluator and publishes the computed
score rather than the reviewer-supplied value. A numeric mismatch is
`goal-score-mismatch`; an absent, empty, foreign, or Neutral/Final-divergent
evidence map is `goal-score-evidence-unbound`. Both are repairable HOLD states
whose fixed next action is `RERUN_TRI_QUERY`.

## Docker Dynamic Grade

An optional ready-last `awx.docker-autograder.result.v1` is frozen into the
tri-query subject input map. Its sibling `job.json` must be ready-last, use
`purpose=GREEN_VERIFICATION`, bind the current intent SHA as
`intentSpecSha256`, bind the current GREEN verification SHA as
`decisionSha256`, declare the exact GREEN command, and include every intent
target. The result must bind that job SHA and prove local-temp-copy, read-only
input/root filesystem, network none, pull never, non-root, dropped
capabilities, no-new-privileges, and resource limits.

The helper recomputes `passed = total - failed - errored - skipped` and the
pass ratio. Submitted count or ratio disagreement, zero tests, timeout,
identity/hash mismatch, malformed isolation, source mutation, or secret hits
is an invalid grade: no delta is applied and the result is HOLD. A valid but
failing GREEN computes its delta but also adds `autograder-green-failed`.

```text
delta = clamp(20 * (passRatio - 0.5), -10, +10)
goalScore = clamp(goalScoreBase + delta, 0, 100)
```

The output records `goalScoreBase`, `autograderPassRatio`, `autograderDelta`,
`dynamicGrade`, and adjusted `goalScore`. The delta is applied identically to
A-B and B-A; it cannot turn an order-unstable review into COMPLETE. When no
Docker evidence is supplied, `dynamicGrade.mode=NOT_PROVIDED` and the base
score is unchanged.

## Paired Integrity Metrics

Before and after records must both use
`awx.memory_integrity.feedback.v1`, `mutationAllowed=false`, and equal checksum
seed, population count, and sample count. Report after-minus-before deltas for:

- `checksumMismatchRate`;
- `contextContaminationRate`;
- `desktopErrorRate`;
- `pairedIntegrityContribution`.

If either value is null, the delta is null and remains evidence-needed. A seed
or population mismatch is not causal ablation evidence.

## Completion Evidence

Require the current guard schemas:

- `awx.macsrc_smb_patch_session.v2`;
- `awx.macsrc_smb_patch_completion.v2` with `state=COMPLETE`, or
  `awx.macsrc_smb_patch_abort.v2`;
- `awx.macsrc_smb_patch_verification.v1`.

Verification must bind the session SHA-256, exact GREEN command, exit code 0,
and every intent target's current postimage SHA-256. Completion must contain the
same postimages, the exact `verificationEvidenceSha256`, a positive and
recomputed `changedFileCount`, zero undeclared changes, and zero secret hits.
Intent, guard session, and integrity roots must match the canonical root. A
no-op completion is `no-source-change`, never COMPLETE.

## Supabase Evidence

When the intent uses `REQUIRED_READ_ONLY`, accept only supporting
`awx.supabase.readonly-evidence.v1` with:

- `authPresent=true`, `projectRefPresent=true`;
- a SHA-256 project-ref hash, never the ref itself;
- `readOnly=true`, allowlisted feature groups, and
  `mutationPerformed=false`.

This follows the official MCP controls for `read_only=true`, `project_ref`, and
restricted `features`: https://supabase.com/docs/guides/ai-tools/mcp

This caller-authored JSON is classified
`CALLER_ASSERTED_PROJECT_SCOPED_READ_ONLY` with unverified provenance; it does
not establish provider runtime lineage. `runtimeLineageVerdict` remains HOLD.

Do not query production or use SQL results as instructions. Missing auth or
scope produces `supabase-auth-missing` or
`supabase-project-ref-missing`. No postprocess verdict authorizes SQL, migration,
policy, RLS, Auth, Storage, or data mutation.

## Output

The helper writes `awx.patch-postprocess.v1` plus `.sha256` and a `.ready`
marker published last. It contains input hashes, sorted failure classes,
paired metric deltas, ordered review status, dynamic grade, redacted Supabase
status, one next action, `nextMutationAllowed=false`, `runtimeLineageVerdict=HOLD`, and
`desktopFinalProof=evidence_needed`.

Consumers must copy machine tokens verbatim. They must not turn
`REPAIR_VERIFICATION_EVIDENCE`, `RERUN_INTEGRITY_WITH_SAME_SEED`,
`COLLECT_SUPABASE_READ_ONLY_EVIDENCE`, `RERUN_TRI_QUERY`, `STOP`, or any failure
class into narrative text or a locally invented alias.
