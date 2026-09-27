# Memory Integrity Feedback Contract

## Scanner Output

Schema: `awx.memory_integrity.feedback.v1`.

The scanner emits JSON only and never edits application source. Important
fields are:

- `verdict`: `HOLD`, `AUDIT_ONLY`, `APPLY`, or `NO_PATCH_NEEDED`.
- `sourceReadiness`: source-gap classification independent of owner authority.
- `patchAuthorizationEligible`: true for an eligible Desktop-local run or an
  explicit `MacSrcSmbDirect` run that must still pass the named direct-patch
  guard.
- `writePolicy`: selected source-write mode, unrestricted external read/tool
  policy, MacSrc root match, OneDrive default=false, and required guard skill.
- `failureClassification`: one redacted reason code.
- `checksumProbe`: seeded SHA-256 sample manifest with path hashes only.
- `observedMetrics`: aggregate values from an optional JSONL probe ledger.
- `goalQueries`: exactly positive, negative, and neutral packets.
- `patchQueue`: at most four ordered source directives.
- `mutationAllowed`: always false for the scanner.

## Deterministic Checksum Probe

Normalize each active-source relative path, rank it by
`SHA256(seed + "|" + path)`, and take the first bounded percentage. Store only
`pathHash` and `contentSha256`. Passing a previous scanner JSON through
`-BaselinePath` measures comparable checksum mismatches without exposing file
names or content.

The sample is pseudo-random but replayable. Do not use `Math.random()` or an
unrecorded runtime seed for ablation evidence.

## Probe Ledger Input

`-ProbeLedgerPath` accepts one JSON object per line. Allowed aggregate fields:

```json
{"checksumMatch":true,"contaminationDetected":false,"expectedContamination":false,"outcomeError":false,"treatment":"control","pairedRunId":"p-001"}
```

Do not include raw query, context, prompt, secret, user identifier, or provider
payload fields. Invalid rows are counted and ignored.

## Quantitative Metrics

| Metric | Formula | Use |
| --- | --- | --- |
| `checksumMismatchRate` | mismatches / probes | corruption signal |
| `contextContaminationRate` | detections / probes | observed pollution |
| `falsePositiveRate` | false alarms / labeled clean | guard cost |
| `falseNegativeRate` | misses / labeled contaminated | guard risk |
| `desktopErrorRate` | erroneous outcomes / Desktop rows | primary outcome |
| `pairedIntegrityContribution` | control error rate - integrity treatment error rate | ablation contribution |

Report denominators. A null rate means `evidence_needed`, not zero. For causal
claims, use paired runs with the same query fixture, seed, source revision, and
runtime configuration; change only the integrity treatment.

## Failure Classes

Fail closed on `macsrc-root-mismatch`, `desktop-owner-or-root-unproven`,
`index-lock-present`, and `secret-leak-risk`. Outside explicit MacSrc direct
mode, also fail closed on `smb-repo-owner-mismatch`, `git-metadata-missing`, and
`git-probe-failed`. In explicit MacSrc direct mode, record Git failure as
`gitEvidenceMode=filesystem-cas` and require `$demo1-macsrc-smb-direct-patch`.
Never copy native Git stderr or secret values into output.
