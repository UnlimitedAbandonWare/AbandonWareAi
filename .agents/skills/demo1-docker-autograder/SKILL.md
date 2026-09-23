---
name: demo1-docker-autograder
description: Use when demo-1 needs a focused pytest or Gradle/JUnit test executed in an isolated
---

# Demo1 Docker Autograder

Execute only an immutable, ready-last job packet. Treat the result as supporting evidence; Desktop retains final verification ownership.

## Preconditions

1. Prove the root and active source boundary first. For production, the root must resolve exactly to `\\desktop-m5nov6k\MacSrc`.
2. Use `$demo1-macsrc-defect-intake` before a `RED_PROBE` and `$demo1-macsrc-patch-postprocessor` after a `GREEN_VERIFICATION`.
3. Never mount the SMB root into Docker. The controller copies only allowlisted files into a host-local temporary directory, mounts that copy read-only, and removes it afterward.
4. Require a digest-pinned image already present locally. Network is `none` and pull policy is `never`; dependency acquisition is a separate, explicitly authorized preparation step.

## Execute

Create the ready-last job with `scripts/new_docker_autograder_job.ps1`. Pass the
two immutable upstream files: RED uses decision + intent spec; GREEN uses
verification + PatchIntent. The builder binds their hashes and fixes network,
pull, and mutation policy. Then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\demo1-docker-autograder\scripts\invoke_docker_autograder.ps1 `
  -Root \\desktop-m5nov6k\MacSrc -RunId <runId> `
  -JobFile data\agent-handoff\docker-autograder\<runId>\job.json `
  -OutputPath data\agent-handoff\docker-autograder\<runId>\result.json
```

Do not add arbitrary container arguments. The controller owns CPU, memory, PID, timeout, non-root, read-only filesystem, capability, and no-new-privileges constraints.

## Interpret

- `executionStatus=COMPLETE` means a bounded JUnit report was parsed; use `testVerdict` and `tests.passRatio`.
- `RED_PROBE` is useful only when the intended failure signal matches and the test fails for the claimed seam.
- `GREEN_VERIFICATION` is useful only when tests pass and immutable decision, intent, job, and source hashes remain coherent.
- `executionStatus=HOLD` is infrastructure or evidence failure, never a test failure and never zero score.
- Never promote an intent from missing, stale, checksum-mismatched, timed-out, malformed, or zero-test evidence.

## Operational Contract

For an already reconciled whole-directive completion, the PowerShell wrapper
accepts `-CompletedDirectiveRequest <exact-cleanup-request>` together with
`-CleanupLogDirectory <cleanup-log-directory>`. It invokes
`$demo1-completed-directive-cleanup` only after a valid passing
`GREEN_VERIFICATION`. The cleanup helper independently requires a final
verified source report and current postimages; a passing grade alone never
retires a directive. If final acceptance follows grading, finalize the report
first and invoke the cleanup skill from canonical intake. Cleanup failure is
reported separately from the preserved grading result and never replays a patch.
When a selected disposable Markdown directive already has that complete final
report, automatically prepare its exact cleanup request and supply both options
under the standing cleanup authorization. Do not make the user request cleanup
again. When grading is needed to produce the final report, perform cleanup
immediately after finalization instead of marking a pending report complete.

- Trigger: repeatable focused RED/GREEN execution whose dependencies are already available in a digest-pinned image.
- Non-trigger: application-source editing, image building/pulling, dependency download, broad exploratory test runs, or Desktop final proof.
- Owner/mutation: tooling owns handoff artifacts only; source mutation and container network are forbidden.
- Bounded output: persist test counts, ratios, duration, hashes, byte counts, truncation booleans, and reason codes; do not persist raw logs.
- Failure mode: fail closed for path traversal, reparse points, secrets, changed checksums, missing Docker, timeout, resource exhaustion, and malformed JUnit.
- Rollback: remove this skill directory and the pipeline `GradeSandbox` transition; source files are unaffected.
- Non-duplication: this skill executes and scores focused tests; intake prepares intent, guarded session patches, and postprocessor adjudicates.
- Falsifying test: `scripts\demo1_docker_autograder_contract_tests.ps1` must reject source-path output, mutated job bytes, and fixture bypass against the production share.

Read [references/docker-autograder-contract.md](references/docker-autograder-contract.md) for schemas, state transitions, and scoring rules.
