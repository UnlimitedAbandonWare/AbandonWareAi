# Docker Auto-Grader Design

## Objective

Add a fail-closed Docker auto-grader to the existing MacSrc evidence pipeline.
The grader must reproduce focused RED evidence before intent promotion, verify
GREEN after a guarded patch, and contribute a bounded dynamic score without
changing the Positive/Negative/Neutral base-score calculation.

## Approved Architecture

Use one reusable Docker runner in two purposes:

```text
Finalize
  -> Docker RED_PROBE
  -> PromoteIntent
  -> PlanSession
  -> guarded patch
  -> Docker GREEN_VERIFICATION
  -> Patch Postprocessor
```

`RED_PROBE` proves that the focused test command fails for the expected reason.
It does not modify GoalScore. `GREEN_VERIFICATION` proves the patched candidate
and supplies the only Docker-derived score adjustment. A Docker infrastructure
failure is evidence failure, not a test failure and not a numeric score.

## Components

### Repo-local skill

Create `.agents/skills/demo1-docker-autograder` with:

- `SKILL.md` for triggers, boundaries, commands, stop rules, and rollback;
- `agents/openai.yaml` for discovery metadata;
- `references/docker-autograder-contract.md` for schemas and exact policies;
- `scripts/docker_autograder.py` for staging, Docker execution, timeout,
  JUnit XML parsing, scoring inputs, redaction, and ready-last publication;
- `scripts/invoke_docker_autograder.ps1` for MacSrc root normalization,
  immutable input validation, and the Python process boundary.

Do not add a second orchestration framework. The skill is the deterministic
execution seam reused by defect intake and terminal postprocessing.

### Pipeline integration

Extend `advance_autograder_probe.ps1` with `GradeSandbox`:

1. require ready-last `decision.json`, `intent-spec.json`, and
   `grade-job.json`;
2. require decision `APPLY` and exact decision/intent-spec SHA bindings;
3. call the Docker auto-grader in `RED_PROBE` purpose;
4. publish ready-last `grade-transition.json` containing job/result hashes;
5. leave the decision and tri-query packets immutable.

`PromoteIntent` must require the grade transition. It may proceed only when the
Docker execution completed, at least one test ran, the exit code is nonzero,
the JUnit result contains a failure or error, and the expected RED signal was
matched in bounded output. After the existing intent builder publishes the
PatchIntent, promotion derives `awx.red-evidence.v1` from the immutable Docker
result and binds it to the new intent SHA. `PlanSession` consumes that record.

The dispatch order becomes:

```text
Positive + Negative -> Neutral -> Finalize -> GradeSandbox
-> PromoteIntent -> PlanSession
```

### Postprocessor integration

Add optional `-AutograderEvidenceFile` to `new_postprocess_packet.ps1`.
When supplied, the record must be ready-last
`awx.docker-autograder.result.v1`, match the run, use
`purpose=GREEN_VERIFICATION`, and bind current input hashes. Recompute all test
counts and the pass ratio from the JUnit summary; never trust a submitted score
or ratio.

Publish:

- `goalScoreBase`: deterministic tri-query score;
- `autograderPassRatio`;
- `autograderDelta`;
- `goalScore`: adjusted score.

Existing callers without Docker evidence retain the base score and report
`dynamicGrade.mode=NOT_PROVIDED`. A supplied but invalid grade yields HOLD.

## Job and Result Contracts

`awx.docker-autograder.job.v1` contains only:

- run ID and `purpose=RED_PROBE|GREEN_VERIFICATION`;
- SHA-256 bindings to the decision and intent specification when applicable;
- `profile=pytest|gradle-junit`;
- a digest-pinned image reference containing `@sha256:`;
- explicit relative include paths and validated test selectors;
- expected RED signal for RED purpose;
- CPU, memory, PID, timeout, file-count, byte-count, and log-byte limits;
- `networkMode=none`, `pullPolicy=never`, and `mutationAllowed=false`.

`awx.docker-autograder.result.v1` contains:

- job and staged-input manifest hashes;
- execution status, failure classification, exit code, and timeout boolean;
- total, passed, failed, errored, skipped, and pass ratio;
- stdout/stderr byte counts, SHA-256 values, and truncation booleans;
- expected-signal match boolean without raw log content;
- exact Docker isolation policy booleans;
- `mutationAllowed=false` and `desktopFinalProof=evidence_needed`.

Both job and result use JSON, sibling `.sha256`, and `.ready` published last.

## Filesystem Isolation

Never give Docker the SMB/UNC path. Resolve and validate every include path
under `\\desktop-m5nov6k\MacSrc`, reject rooted paths, `..`, colon/alternate
streams, symlinks, junctions, and other reparse points, then copy only the
allowlisted files to a host-local temporary directory.

Reject a temporary root that is UNC, exceeds the declared file/byte bounds, or
contains a prohibited `.git`, `.gradle`, `build`, `node_modules`, secret, key,
or credential path. Hash every copied file and compare its source hash again
after copying to detect concurrent SMB changes. Always delete staging after
the result record has been published.

Mounts are:

```text
local staging/input -> /input   read-only
tmpfs -> /workspace             writable ephemeral work copy
tmpfs -> /tmp                   writable ephemeral temp
bounded tmpfs -> /results       writable JUnit only; no host bind
```

The container copies `/input` into `/workspace` before executing the fixed
profile command. It cannot write the source or SMB share. At completion it
streams an uncompressed tar of `/results` through stdout; the controller
validates entry paths/types/counts/sizes before extracting to local staging.

## Docker Isolation Policy

Every test container uses:

- `--network none` and no published ports;
- `--pull never` with a digest-pinned, preloaded image;
- `--read-only` root filesystem;
- `--cap-drop ALL` and `--security-opt no-new-privileges`;
- non-root `--user 65532:65532`;
- bounded `--cpus`, `--memory`, equal `--memory-swap`, and `--pids-limit`;
- bounded tmpfs sizes, `--ulimit nofile=1024:1024`, and `--stop-timeout 1`;
- a unique validated container name and host-enforced timeout;
- no Docker socket, host PID/IPC/network, devices, privileges, environment
  dump, credential mount, or host cache mount.

Dependency downloads never occur in the grading container. A missing image or
dependency returns `dependency-prefetch-required`. An operator may separately
build or preload a digest-pinned image through an explicitly approved,
egress-filtered process; the auto-run pipeline never enables network access.

## Test Profiles

`pytest` runs a generated fixed command that copies input to `/workspace`,
invokes `python -m pytest` with validated selectors, and writes
`/results/junit.xml`. Test logs are redirected to the bounded log channel and
stdout is reserved for the JUnit tar transport.

`gradle-junit` invokes the copied wrapper with `--offline`, `--no-daemon`, a
workspace-local project cache, and validated `--tests` selectors. It copies
generated `TEST-*.xml` files to `/results` even when tests fail. The image must
already contain the required JDK, dependency state, POSIX shell, and `tar`.

Selectors may not begin with `-`, contain shell metacharacters, be rooted, or
escape the staged tree. The runner generates the shell command and quotes every
validated token; callers cannot supply arbitrary shell text.

## Result Parsing and Scoring

Parse only bounded JUnit XML. Reject DTD/entity declarations, oversized XML,
more than the declared report/test count, negative counts, inconsistent totals,
and missing reports. Treat skipped tests as not passed:

```text
passed = total - failed - errored - skipped
passRatio = passed / total
dynamicDelta = round(20 * (passRatio - 0.5), 4)
adjustedGoalScore = clamp(goalScoreBase + dynamicDelta, 0, 100)
```

The delta range is `-10..+10`. Zero tests, timeout, Docker unavailability,
missing image/dependency, malformed reports, or policy mismatch produces no
delta and forces HOLD. A GREEN result with any failure, error, skip, or nonzero
exit is `autograder-green-failed` and cannot produce COMPLETE.

The tri-query evaluator still computes `goalScoreBase` before Docker evidence
is read. Docker evidence is a deterministic post-adjudication input, so it
cannot change Positive/Negative visibility, packet hashes, Neutral A-B/B-A
order, or `orderStable`.

## Logging and Redaction

Capture stdout and stderr to local temporary files rather than memory. Scan
only within declared byte limits, compute hashes and expected-signal presence,
then delete the raw files with staging. Never publish raw output, environment
values, queries, prompts, tokens, authorization headers, cookies, or provider
payloads. Store counts, hashes, booleans, timing, and reason codes only.

## Failure Policy

Fail closed for:

- `docker-cli-unavailable`, `docker-daemon-unavailable`, or timeout;
- nonlocal staging, UNC mount emission, traversal, reparse, or source drift;
- mutable/unpinned image, image pull attempt, or network other than none;
- resource flag omission or unsafe Docker option;
- missing, malformed, inconsistent, or oversized JUnit results;
- secret-like content in job/result artifacts;
- missing ready markers or checksum mismatch;
- stale decision, intent-spec, promotion, or postprocessor input bindings.

No failure changes global Git `safe.directory`, copies source to OneDrive,
mutates application source, acquires a source lease, or manufactures a PASS.

## Verification Strategy

Use RED/GREEN contract tests with a real subprocess fake Docker CLI. The fake is
the external-process boundary and must emit controlled JUnit XML; tests assert
observable result files and captured argv rather than source text.

Required cases:

1. runner is missing before implementation;
2. successful pytest report produces exact counts and a local read-only mount;
3. partial report produces the literal hand-calculated ratio and delta;
4. timeout invokes kill and publishes HOLD without a score;
5. UNC, traversal, reparse, oversized input, mutable image, and unsafe selector
   are rejected before Docker execution;
6. Docker argv contains every network/resource/security flag and no SMB path;
7. GradeSandbox is required before PromoteIntent and binds all hashes;
8. RED result derives intent-bound RED evidence without rerunning or mutation;
9. postprocessor recomputes the ratio/delta and rejects forged values;
10. Positive/Negative/Neutral packet hashes and A-B/B-A order remain unchanged;
11. existing defect-intake, session, GoalScore, memory, and postprocess suites
    remain green;
12. Docker-missing live smoke returns `docker-cli-unavailable` and remains
    evidence-needed rather than PASS.

## Ownership, Rollback, and Proof

This feature owns only repo-local skill/tool/test files and derived handoff
records. It does not own application source, Gradle dependencies, Docker
installation, daemon configuration, global Git configuration, Supabase, or
Desktop runtime proof.

Rollback removes the new skill and runner, removes the new optional pipeline
mode and postprocessor argument, and restores the prior dispatch order. Derived
grade records can be discarded because they are immutable evidence artifacts;
no application-source rollback is involved.

Current host evidence reports `docker-command-missing`. Unit and contract
tests can prove command construction, staging, parsing, timeout, scoring, and
fail-closed behavior. A real digest-pinned pytest and Gradle/JUnit container
smoke remains `desktopFinalProof=evidence_needed` until Docker is installed and
the required images are preloaded on the execution owner.
