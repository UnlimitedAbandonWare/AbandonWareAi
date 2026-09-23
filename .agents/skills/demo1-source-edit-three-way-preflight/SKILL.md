---
name: demo1-source-edit-three-way-preflight
description: "Use before any Desktop demo-1 source write: three-way preflight gate"
---

# Demo1 Source-Edit Three-Way Preflight

Freeze one redacted EvidenceSnapshot and create exactly three logical review
packets before application-source mutation. The default process mode is
`single-agent-logical-roles`; do not spawn mandatory subagents.

## Fixed workflow

1. Confirm Desktop root, active sourceSets, declared targets, live file hashes,
   applicable source-owner guard, and verification commands. Do not run
   `git status`/`branch` as an ordinary source-edit gate (`AGENTS.md`
   `DEMO1-GIT-LOCAL-FIRST`). Git index/ref writes still use their own lock
   contract; an `index.lock` observation alone does not fail this preflight.
2. Freeze at most 20 evidence rows and one `evidenceSnapshotHash`. Use paths,
   hashes, counts, booleans, timings, and redacted reason codes only.
3. Run `POSITIVE_QUERY` over the frozen snapshot. Create two to four falsifiable
   scenario IDs.
4. Run `NEGATIVE_QUERY` over the same snapshot and exact Positive scenario-ID
   set. Acquire no evidence.
5. Run `NEUTRAL_QUERY` over Positive-Negative and Negative-Positive. Acquire no
   evidence and apply the fixed goal-score formula.
6. Set HOLD when order or decisive-evidence sets differ, score is below 50,
   ownership/verification is missing, or a safety gate fails.
7. On stable APPLY, emit `nextWorkflow=existing-source-owner-guard`; this skill
   never edits source itself. On HOLD/REJECT emit `nextWorkflow=none` and one
   `nextSingleProof`.

## Invariants

```text
canonicalQueryCount=3
processMode=single-agent-logical-roles
no-fourth-reviewer=true
majorityVote=false
neutralMayAcquireEvidence=false
rawPromptStored=false
rawResponseStored=false
largeArtifactMode=path-plus-hash
desktopFinalProof=evidence_needed
```

Read [preflight-contract.md](references/preflight-contract.md) for exact packet
fields, size bounds, score, and failure classes. Route an explicitly authorized
Notebook Y-drive source edit to the existing repository guard; do not create a
second lease, compare-and-swap, PatchDrop, or mutation protocol.

## Stop conditions

Stop with HOLD for `source-owner-unproven`, `dirty-overlap`,
an index-dependent operation with `index-lock-present`, `snapshot-unfrozen`, `scenario-coverage-mismatch`,
`order-unstable`, `canonical-query-count-invalid`, missing verification, or
`tri-preflight-timeout` after `preflightWallClockMaxSeconds=120`. Reject on
redaction failure or undeclared source write.

For an index-independent edit or a prior GPU HOLD, use `scoped-blocker-recovery`
to establish the operation scope. A lock observation alone does not fail this
preflight: require the existing scoped lease, writer, target-manifest and immediate
preimage checks. This changes no query count, score, order-stability, or authority gate.
