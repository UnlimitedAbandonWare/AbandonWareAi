# Kit B — F01–F04

Activate only after Kit A has a recorded exit code and `CURRENT_KIT.md` is `B`.
One F per patch. Do not open F05–F12 from this kit.

The names below are proposals in
`C:\Users\nninn\Downloads\maiaswsn_performance_directive_v2_2026-09-28.md`.
That directive says the names are to be added. They are not classes in this
checkout (sample search on 2026-09-28 found none). Codex adds the RED test in
the live source set. A ZIP line number is not a live anchor.

## One-F checklist

- Stay inside the one F. Do not fold the next F into the same diff.
- F01-A and F01-B are different effects. A separates the summary network call
  from the run lock and the answer wait. B places the derived summary after
  the approved original save. A green A is not a claim that answer latency is gone.
- F02 must not rank a lane higher because it finished first, and it must not
  give the first lane the whole budget.
- F03 admission or rejection and the cancel handle are one contract. The handle
  is the queued object. A higher rejection rate that only improves p95 is a fail.
- F04 dedup inside one batch and single-flight across overlapping batches are
  separate measurements. Do not multiply them into one speedup.
- Superpowers: one cause, one RED test, then the smallest change.
- AWX only after a compile, test, or boot failure:

```powershell
python -B tools/build_error_miner.py scan --in <failure-log> --out data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/verify-build-error/report
```

The miner classifies a log. It does not patch.

Read-only executor and pool counts (no line text):

```powershell
python -B scripts/max_push_executor_log_scan.py --root .
```

`not_observed` means the log dir was missing. It is not a pass of the pool.

## Proposed tests and original files

| Item | Proposed test | Original file cited by the directive |
|---|---|---|
| F01 | `UnderstandingPreparationDoesNotHoldRunGateTest` | `main/java/com/example/lms/learning/gemini/LearningWriteInterceptor.java` |
| F01 | `LateUnderstandingCannotResurrectDeletedSourceTest` | `main/java/com/example/lms/api/ChatApiController.java` |
| F01 | `UnderstandingTaskExactlyOncePerRevisionTest` | `main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java` |
| F01 | `NextTurnUsesRecentTranscriptWhileDerivedSummaryPendingTest` | same F01 files |
| F01 | `UnderstandingQueueFailureIsNotPersistedTest` | same F01 files |
| F01 | `OldRunCannotEmitUnderstandingIntoNewRunTest` | same F01 files |
| F02 | `CompletedLaterAttemptIsHarvestedBeforeSlowHeadTest` | `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java` |
| F02 | `CompletionOrderDoesNotChangeFusedEvidenceTest` | same |
| F02 | `BranchRetryDoesNotResetDeadlineTest` | same |
| F02 | `SubmissionTimeConsumesLevelBudgetTest` | same |
| F02 | `InterruptedCollectorCancelsAllOutstandingAttemptsTest` | same |
| F03 | `SaturatedSearchNeverRunsProviderOnSubmitterTest` | `main/java/com/example/lms/config/SearchExecutorConfig.java` |
| F03 | `CancelledWrappedTaskReleasesQueueSlotTest` | `main/java/com/example/lms/infra/exec/ContextAwareExecutorService.java` |
| F03 | `CancellationDoesNotReleaseActiveProviderPermitEarlyTest` | same F03 files |
| F03 | `RejectedAndCancelledAttemptsCompleteExactlyOnceTest` | same F03 files |
| F03 | `SearchContextRestoredAfterCancellationTest` | same F03 files |
| F04 | `EmbedAllDeduplicatesCanonicalKeysAndRestoresPositionsTest` | `main/java/com/example/lms/service/embedding/DecoratingEmbeddingModel.java` |
| F04 | `OverlappingBatchesComputeOnlyOwnedKeysTest` | `main/java/com/example/lms/service/embedding/EmbeddingCache.java` |
| F04 | `CrossOwnedBatchesDoNotDeadlockTest` | same F04 files |
| F04 | `BatchFallbackDoesNotJoinItsOwnReservationTest` | same F04 files |
| F04 | `FollowerTimeoutDoesNotCancelOtherWaitersTest` | same F04 files |
| F04 | `InvalidationRejectsLateOwnerPublicationTest` | same F04 files |
| F04 | `BatchPartialFailureReleasesAllReservationsTest` | same F04 files |

After Codex adds a class, the focused command is:

```powershell
. .\scripts\max_push_kit_env.ps1
$out = "data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/verify-f0x"
python -B scripts/run_verified_command.py --output $out -- .\gradlew.bat test --tests <one.F0x.suite> --no-daemon --console=plain
```
