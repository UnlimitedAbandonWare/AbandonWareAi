# Memory Integrity Source Directive

## Goal Selection

Build all three packets from one EvidenceSnapshot:

1. `POSITIVE_QUERY`: retain proven seams, reusable tests, and the smallest
   successful integrity path.
2. `NEGATIVE_QUERY`: seek ownership, privacy, causality, nondeterminism, cost,
   and false-positive counterexamples.
3. `NEUTRAL_QUERY`: compare Positive-vs-Negative and Negative-vs-Positive. Do
   not invent facts. Set `HOLD` when the order changes the verdict, a score is
   below 50, or any hard gate fails.

Use the repository GoalContract score formula. Record every 0..1 component and
its evidence ID. Highest score alone never overrides a safety gate.

## Ordered Patch Queue

### 1. Memory Checksum Stamp and Verify

- Candidate boundary: `TraceSnapshotStore` and
  `TraceMemoryFingerprintProbe`; confirm the live call path first.
- RED: canonical snapshot tampering is accepted or cannot be classified.
- GREEN: stable canonical serialization emits/verifies SHA-256 and returns
  `checksum_mismatch` without retaining raw context.
- Rollback: remove only the checksum fields and focused tests added in this
  cycle.

### 2. Deterministic Integrity Ledger

- Candidate boundary: snapshot/probe sampling and its trace ledger.
- RED: identical source population and seed select different path hashes.
- GREEN: same revision, population, and seed replay the same bounded set;
  different seeds may select a different set.
- Record seed, population, sample count, path hash, checksum, timing, and reason
  code only.

### 3. Paired Integrity Ablation

- Candidate boundary: `AblationContributionTracker`; do not add a second
  tracker if the live class can be extended.
- RED: control and treatment errors cannot be joined by `pairedRunId`.
- GREEN: output both denominators, both error rates, and
  `controlErrorRate - integrityTreatmentErrorRate`.
- Reject a contribution claim when source revision, query fixture, provider,
  seed, or runtime configuration differs between the pair.

### 4. Autograder Probe-Only Loop

- Candidate boundary: existing autograder/orchestrator seam proven by call
  evidence.
- RED: autograder mode skips integrity probes or patches on an unclassified
  anomaly.
- GREEN: autograder runs the bounded read-only probe first, enqueues one source
  item only for a classified mismatch, verifies it, and rescans.
- Stop after four items, 180 minutes, a changed blocker, failed GREEN, secret
  risk, or authority expansion.

## Verification Order

1. Focused RED/GREEN test for the selected class.
2. `gradlew.bat compileJava -x test` with host-local Gradle caches. For a
   Notebook `MACSRC_SMB_DIRECT` session, keep build outputs and project caches
   host-specific and retain Desktop final proof as `evidence_needed`.
3. `gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene` when
   available.
4. Rerun the scanner with the same seed and optional ledger.

For `MACSRC_SMB_DIRECT`, wrap the declared source targets with
`$demo1-macsrc-smb-direct-patch` Prepare/Verify/Complete evidence. Keep
`desktopFinalProof=evidence_needed` until the required commands are observed in
the Desktop owner session. Public API, DB, credential, environment-name,
deployment, commit, and push changes are forbidden without separate approval.
