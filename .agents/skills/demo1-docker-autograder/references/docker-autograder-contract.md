# Docker Autograder Contract

## Artifact layout

All persistent artifacts are beneath `data/agent-handoff/docker-autograder/<runId>/`. Each JSON artifact is written atomically and becomes readable only after matching `.sha256` and `.ready` files exist. Consumers read the JSON once, hash the captured bytes, and validate the sidecar and ready marker against that same snapshot.

## Job schema

`awx.docker-autograder.job.v1` has an exact field set:

- `runId`: bounded identifier.
- `purpose`: `RED_PROBE` or `GREEN_VERIFICATION`.
- `profile`: `pytest` or `gradle-junit`.
- `imageRef`: image reference containing `@sha256:<64 lowercase hex>`.
- `includePaths`: 1–128 relative regular-file paths. No rooted path, drive/ADS colon, traversal, reparse chain, VCS/cache/build directory, or secret-looking content.
- `testSelectors`: 1–64 bounded selectors; option injection is rejected.
- `declaredTestCommand`: bounded, single-line evidence label copied into RED/GREEN provenance. It is never executed by the controller.
- `expectedSignal`: required for RED, bounded for GREEN.
- `decisionSha256`, `intentSpecSha256`: immutable upstream bindings.
- `limits`: bounded `cpus`, `memoryMb`, `pids`, `timeoutSeconds`, `maxFiles`, `maxInputBytes`, `maxLogBytes`, `maxXmlBytes`, and `maxTestCases`.
- `networkMode`: exactly `none`.
- `pullPolicy`: exactly `never`.
- `mutationAllowed`: exactly `false`.

Unknown fields are rejected. The job is an execution description, not a shell escape hatch; callers cannot provide arbitrary commands or Docker options.

Use `scripts/new_docker_autograder_job.ps1` to publish the job, SHA sidecar,
and ready marker atomically. For RED, `decisionSha256` and
`intentSpecSha256` retain their literal meanings. For GREEN, they bind the
current GREEN verification and PatchIntent respectively; the postprocessor
checks those bindings before applying a dynamic score.

## Container contract

The controller uses a host-local temporary staging directory. Only allowlisted files are copied from MacSrc, and source hashes are checked before and after copying. Docker receives only the staged input bind as read-only. JUnit files stay in a size-limited container tmpfs and leave the container only as a bounded uncompressed tar stream; `/results` is never a host bind. The root filesystem is read-only. Capabilities are dropped, no-new-privileges and a non-root UID/GID are used, network and pulls are disabled, and CPU/memory/PID/timeout limits are mandatory. The staging directory is removed in `finally`.

## Result schema

`awx.docker-autograder.result.v1` binds `runId`, `purpose`, `jobSha256`, and `inputManifestSha256`. It records:

- `executionStatus`: `COMPLETE` or `HOLD`.
- `failureClass`, nullable `exitCode`, `timedOut`, and `testVerdict`.
- `tests`: total, passed, failed, errored, skipped, and nullable pass ratio.
- log total and persisted byte counts, SHA-256 hashes, truncation flags, a bounded JUnit archive hash/size, and the deterministic combined log hash. Pipe readers drain all output but persist only configured bounds.
- `expectedSignalMatched`, isolation booleans, count-only secret results, and mutation booleans.

Raw logs and individual test names are not persisted. JUnit XML is bounded, rejects DTD/entity declarations, and must contain a nonzero internally consistent test count.
The container tmpfs provides the hard JUnit byte cap. The controller also
validates tar paths/types/counts/sizes, checks each extracted XML size before
materializing bytes, and rejects a report that changes during the read.

## Pipeline transitions

`Finalize -> GradeSandbox(RED_PROBE) -> PromoteIntent -> PlanSession` is the preparation path. Promotion requires a COMPLETE failing result whose expected signal matches and whose hashes bind the decision and intent specification.

After a guarded patch, `GREEN_VERIFICATION` is consumed by postprocessing. Infrastructure HOLD is not converted to a dynamic score. A valid execution delta is deterministic:

```text
dynamicDelta = clamp(20 * (passRatio - 0.5), -10, +10)
finalGoalScore = clamp(recalculatedStaticGoalScore + dynamicDelta, 0, 100)
```

The delta is applied identically in A-B and B-A adjudication. Order instability remains HOLD. A successful sandbox run remains supporting evidence until Desktop repeats the required final verification.

## Failure classes

Representative fail-closed classes include `docker-cli-unavailable`, `autograder-timeout`, `network-policy-invalid`, `pull-policy-invalid`, `image-not-digest-pinned`, `path-outside-root`, `input-path-prohibited`, `input-reparse-point`, `source-changed-during-stage`, `secret-leak-risk`, `junit-report-missing`, `junit-report-malformed`, `junit-zero-tests`, `resource-limit-invalid`, and checksum/ready mismatches.
`docker-kill-timeout` and `junit-output-limit-exceeded` preserve the more
specific cleanup/resource failure instead of publishing an unclassified HOLD.
