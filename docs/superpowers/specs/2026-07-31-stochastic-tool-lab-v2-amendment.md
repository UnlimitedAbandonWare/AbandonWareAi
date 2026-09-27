# Stochastic Tool Lab v2 safety amendment

Date: 2026-07-31

## Why v2 is required

The v1 runner strictly validates caller-provided measurements but does not produce them. A caller can therefore submit internally coherent fabricated scores. The runner also labels a lab candidate `isolated-promoted` after publishing metadata without materializing the declared payload or verifying the target preimage. Green v1 tests prove schema and scoring behavior, not an authoritative experiment.

V2 removes those two claims. Automatic promotion requires measurements generated inside the runner by a code-registered sealed adapter and real payload/preimage bytes. Unsupported script, executable tool, and behavior-shaping skill candidates remain `HOLD / registered-adapter-needed`; they can never be promoted from caller-provided scores.

## Authoritative boundary

- Remove top-level caller `measurements` from run input v2.
- Require a real bounded `candidatePayloads` descriptor and matching bytes for every candidate.
- Verify target preimage from an open regular non-reparse handle, or prove the target is absent beneath an allowed safe parent.
- Support only `sealed-declarative-policy-v1` for automatic v2 measurement. It evaluates a JSON policy with a code-owned 12-case fixture deck and the code-owned scorer formula. It never executes candidate code or caller commands.
- Use `registered-adapter-needed` for executable scripts/tools and behavior-shaping skills until a separately reviewed isolated adapter exists.
- Treat adapter, payload, and ordinary candidate failures as candidate-local quarantine/reject; continue with the next eligible candidate. Global schema, sealed fixture/scorer integrity, and publication failures fail the run.

## Candidate and selection changes

Candidate v2 adds `adapterId` and replaces the claimed preimage hash with:

```text
targetPreimage = {state: present, sha256, byteCount} | {state: absent}
```

Run input v2 adds:

```text
candidatePayloads[] = {candidateId, relativePath, sha256, byteCount, mediaType}
```

The neutral packet owns exact `candidateAssessments[]`. The runner derives the eligible list by filtering all assessments first, sorting by the existing deterministic preflight key, and then taking at most three. Caller measurements and a separate caller-owned preflight list are rejected.

## Promotion and review behavior

- A lab-only winner is copied to `promoted/<candidateId>/<targetPath>` beneath the run output. `promotion.json` binds the original preimage, payload hash, materialized path, byte count, and postimage hash. The source checkout is unchanged.
- A shared `scripts/**`, `tools/**`, or `.agents/skills/**` winner may emit only a byte-verified `winner.patch` after exact diff-target and current-preimage verification. It is never applied.
- `review-patch` is the only review mutation enum. `review-only-patch` is prose only and must not appear where an enum value is required.

## Required artifacts

Publish candidate bodies and their sidecars first, then:

```text
candidates.json
experiments/<candidateId>/measurement.json or quarantine.json
experiments/<candidateId>/grade.json when measured
final-ranking.json
promoted/... plus promotion.json, or winner.patch
result.json
summary.md
artifact-manifest.json
run.ready
```

The manifest records path, SHA-256, byte count, and artifact role. `run.ready` is the final marker. Result claims stay `claimScope=artifact-quality` and `runtimeLineageVerdict=HOLD`.

## Sealed self-test expansion

Self-test must use the actual fixture-pack bytes and canonical scorer-formula hash. It covers the declarative adapter, measurement forgery rejection, candidate/payload/preimage binding, A-B/B-A order behavior, path/traversal/reparse rejection, protected regression, secret rejection, deterministic replay, and ready-last publication. Scorer-only sentinels are insufficient for a whole-autograder PASS.

## Promotion gates

The existing gates remain non-compensable: 3 seeds × 12 trusted fixtures, mean delta at least 10, worst-seed delta at least 5, zero protected regression, zero hard gates, zero soft-limit violation, and a unique winner. Behavior-shaping skills additionally require five fresh manual-review attestations but still cannot auto-promote without a registered behavior adapter.
