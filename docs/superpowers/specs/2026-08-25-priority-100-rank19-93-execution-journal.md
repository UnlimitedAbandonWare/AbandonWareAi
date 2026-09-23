# Priority 100 shared ranks 19/93 execution journal

## Outcome

- Production seam: `main/java/com/example/lms/transform/QueryTransformer.java`
- Shared disposition changes: ranks `19` and `93`, `HOLD -> FIXED`
- Distinct production patches for the duplicate ranks: `1`
- Actual HOLD reduction: `2`
- Final terminal totals: `FIXED=22`, `NO_PATCH_NEEDED=31`, `HOLD=29`,
  `EVIDENCE_NEEDED=18`
- Next requested cohort: ranks `20` through `26`, beginning with the active
  file-degraded-storage global I/O-lock seam

No commit, staging, push, deployment, database change, provider call, protected
configuration edit, dependency change, or `opnessl` mutation was performed.
Provider and wire execution remain `not_observed`.

## Fresh owner and preimage proof

- repository / worktree: `C:\AbandonWare\demo-1\demo-1\src`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- Java: `17.0.13`
- `.git/index.lock` present: `false`
- Git/Git-LFS process count: `0`
- active source-edit lease count before preflight: `0`
- active top-level PatchDrop patch count: `0`
- pre-rank broad porcelain entries: `1694`
- pre-rank broad porcelain SHA-256:
  `8570877FFB35376C358AB3BE2AB62F19E72ED1931F60DB772EE06F3C15FBF196`
- active sourceSet: root `main/java`; active test sourceSet: `src/test/java`
- owner: Spring component
  `main/java/com/example/lms/transform/QueryTransformer.java`
- active callers: `SmartQueryPlanner`, `HybridKeywordExtractor`,
  `NaverSearchService`, and canonical `service/rag/HybridRetriever`
- frozen production preimage SHA-256:
  `EE3FDC3BE1ED605A57515ADCDD048985FB2AFBFA44E9F7C36DD1C1302087BF7F`
- production file was already modified, but every intended cancellation token
  retained the HEAD preimage: `cancel(false)` at the force-kill and caller
  interruption branches and `Thread.interrupted()` at caller/worker branches
- the neighboring dirty `NightmareBreaker.CallPermit`, cooldown, diagnostic,
  redaction, and fallback hunks were preserved
- owned focused test:
  `src/test/java/com/example/lms/transform/QueryTransformerInterruptContractTest.java`
- owned focused test existed before this session / was ignored by Git:
  `false / false`

## Frozen EvidenceSnapshot

- evidenceRowCount: `20`
- evidenceSnapshotHash:
  `7A36CD1DB737736F35C31548FF0E507BABBB32B1AC8BAEED9E376F8725249842`
- rawPromptStored: `false`

```text
E01|root=C:\AbandonWare\demo-1\demo-1\src|branch=codex/owned-runtime-browser-restart|head=0796a3c5b29bbb08c3314bd40649d856d4a7bce6
E02|gitProcessCount=0|indexLockPresent=false|activeSourceLeaseCount=0|topLevelPatchCount=0
E03|java=17.0.13|rootMain=main/java|rootTest=src/test/java|build.gradle.kts=334-350
E04|owner=main/java/com/example/lms/transform/QueryTransformer.java|component=true|liveSha256=EE3FDC3BE1ED605A57515ADCDD048985FB2AFBFA44E9F7C36DD1C1302087BF7F
E05|targetDirty=true|headTokens=cancelFalse+Thread.interrupted|liveTokens=same|intendedLinesRetainHeadPreimage=true
E06|callers=SmartQueryPlanner:523-524,HybridKeywordExtractor:184,NaverSearchService:1705+1759,HybridRetriever:2095
E07|executorInjection=llmFastExecutor|QueryTransformer=151-153|submit=647-666
E08|executorOwner=SearchExecutorConfig:45-71|boundedThreadPool+ContextAwareExecutorService
E09|cancelShieldAllowlist=searchIoExecutor,llmFastExecutor|postProcessor=75-116|enabledDefault=true
E10|contextAwareSubmitReturnsCancelShieldFuture|ContextAwareExecutorService=53-75+132-137
E11|cancelShieldFutureDowngradesTrueToFalse|CancelShieldFuture=66-110
E12|forceKill=QueryTransformer:669-697|cancelFalseAt674
E13|callerInterrupt=QueryTransformer:757-788|clearsAt759|cancelFalseAt762
E14|workerInterruptedFailure=QueryTransformer:1393-1429|clearsAt1399
E15|candidateFix=ownedFutureTask+execute|cancelTrueOnOwnedHandle|restoreCallerAndWorkerInterrupt
E16|newTest=src/test/java/com/example/lms/transform/QueryTransformerInterruptContractTest.java|exists=false|ignored=false
E17|focusedCommand=test --tests com.example.lms.transform.QueryTransformerInterruptContractTest
E18|affectedCommands=QueryTransformer*Test,checkSourceSetHygiene,checkLangchain4jVersionPurity,test--rerun-tasks
E19|rank19=HOLD|rank93=HOLD|sharedSeam=query-transformer-interrupt-and-termination
E20|porcelainNormalCount=1694|porcelainSha256=8570877FFB35376C358AB3BE2AB62F19E72ED1931F60DB772EE06F3C15FBF196|diffCheckExit=0
```

## Source-edit three-way preflight

- processMode: `single-agent-logical-roles`
- canonicalQueryCount: `3`
- scenario IDs in both packets:
  `R19-CALLER,R19-WORKER,R19-PRESERVE`
- scenarioCoverageEqual: `true`
- decisiveEvidenceEqual: `true`

POSITIVE_QUERY:

- `R19-CALLER`: an interrupted request thread must return through the optional
  fail-soft path with its interrupt status restored.
- `R19-WORKER`: cancellation must reach and terminate the blocked owned worker
  even when the injected `llmFastExecutor` is wrapped by CancelShield.
- `R19-PRESERVE`: the one shared patch must retain existing single-flight,
  context propagation, breaker/cooldown, and dirty neighboring hunks.

NEGATIVE_QUERY used the same IDs and challenged three tempting false fixes:

- `R19-CALLER`: returning an empty string is not interrupt preservation when
  `InterruptedException` has cleared the request-thread flag.
- `R19-WORKER`: changing only `cancel(false)` to `cancel(true)` on the returned
  executor future is ineffective because live CancelShield deliberately
  downgrades it; an interrupt-ignoring external provider also cannot be claimed
  terminated without direct evidence.
- `R19-PRESERVE`: replacing the shared executor or rewriting the whole dirty
  method would expand lifecycle and overlap risk; the task handle can instead
  be owned locally.

NEUTRAL_QUERY:

- forwardOrder / reverseOrder:
  `[POSITIVE_QUERY,NEGATIVE_QUERY]` /
  `[NEGATIVE_QUERY,POSITIVE_QUERY]`
- forwardVerdict / reverseVerdict: `APPLY / APPLY`
- orderStable: `true`
- score inputs: evidence `0.95`, causal `0.95`, verification `0.95`, user value
  `0.90`, reversibility `0.90`, cost efficiency `0.90`, time fit `0.90`, blast
  radius `0.15`, ambiguity `0.10`, authority expansion `0.05`
- goalScore: `87.5`
- verdict: `APPLY`
- nextSingleProof: acquire the lease and require a real blocked worker behind
  CancelShield to survive the old caller-cancellation path
- nextWorkflow: `existing-source-owner-guard`

## Lease and deterministic RED

- lease topic: `priority100-rank19-93-query-transformer-cancel`
- lease owner: `codex-rank19-93-20260825`
- TTL: `180` minutes
- lease acquisition result: `source-edit-locks=1`
- source SHA immediately before lease and again immediately before the
  production patch:
  `EE3FDC3BE1ED605A57515ADCDD048985FB2AFBFA44E9F7C36DD1C1302087BF7F`
- focused test SHA-256:
  `CEA0FA2FA805F0BAA2862D6D37F9D79D5A4209923D33E0E0275B2C27E7C4F2AD`
- RED command: focused `QueryTransformerInterruptContractTest` with split host
  `desktop-rank19`, isolated Gradle user home, and isolated project cache
- RED result: exit `1`; tests `3`, failures `3`, errors `0`, skipped `0`
- expected failures:
  - caller interrupt status was false after fail-soft return, line `48`
  - caller cancellation did not interrupt the blocked worker, line `64`
  - force-kill did not interrupt the blocked worker, line `85`

The fixture uses real caller and worker threads, latches rather than sleeps, the
real `ContextAwareExecutorService`, and the real `CancelShieldExecutorService`.
The worker records both the received interrupt and its interrupt status at the
executor task boundary.

## Minimal shared patch

- create a directly owned `FutureTask<Void>` for the one in-flight LLM call
- place that exact handle in the existing `inflightTasks` map before dispatch
- dispatch it with `ExecutorService.execute(...)`, which retains the existing
  executor context wrapping but does not replace the owned task handle with a
  shielded returned future
- use `cancel(true)` on that owned handle for caller cancellation and force-kill
- restore caller interruption with `Thread.currentThread().interrupt()`
- preserve worker interruption through the end of `runLLM` with the same API
- retain the existing `CompletableFuture` single-flight/cache contract,
  timeout policy, breaker permit completion, diagnostics, and executor lifecycle
- final production SHA-256:
  `5C7D8E100E9F1C762347BA202436614F5B0E95AC6DD332426A1AC5D65D877B16`
- configuration, resource, dependency, DB, provider, and public API signature
  changes: `false`

## GREEN and regression proof

- focused suite: `3/3` GREEN, failures/errors/skips `0/0/0`
- focused XML SHA-256:
  `CB2446E3F7A2576156B630B1C2C8D93A06A4584AD464513E19B0E8D7F4B8BA23`
- affected suites: four QueryTransformer suites plus two CancelShield executor
  suites, `63/63` GREEN, failures/errors/skips `0/0/0`
- `checkSourceSetHygiene`: `BUILD SUCCESSFUL`
- `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`; LangChain4j remains
  exactly `1.0.1`
- full command: `gradlew.bat test --rerun-tasks --no-daemon` with split host
  `desktop-rank19`, isolated Gradle user home, and isolated project cache
- full result: `BUILD SUCCESSFUL in 3m 34s`
- XML aggregation: suites `1016`, tests `6486`, failures `0`, errors `0`,
  skipped `3`
- full XML result-manifest SHA-256:
  `27086F4C510B3ACB0448DD877ED8F61A2ED2C5BB3D7A46D98275044F62848071`
- `git diff --check`: exit `0`
- changed-patch secret-pattern hits: `0`
- changed-patch `opnessl` occurrences: `0`
- runtime/provider/wire execution: `not_observed`

## Final preservation and lease release

- lease release requested / completed UTC:
  `2026-08-25T01:55:54.8743813Z` /
  `2026-08-25T01:55:54.9455012Z`
- lease release result: `source-edit-locks=0`
- post-release active/corrupt/expired lease counts: `0/0/0`
- `.git/index.lock` present: `false`
- Git/Git-LFS process count: `0`
- active top-level PatchDrop patch count: `0`
- final branch / HEAD:
  `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- final worktree: `C:/AbandonWare/demo-1/demo-1/src`
- final broad porcelain entries: `1696`
- intentional new broad-porcelain rows: focused regression test and this
  execution journal
- broad porcelain entries excluding those two paths: `1694`
- filtered porcelain SHA-256:
  `8570877FFB35376C358AB3BE2AB62F19E72ED1931F60DB772EE06F3C15FBF196`
- filtered status equals the exact pre-rank status: `true`
- staged path count: `0`
- `git diff --check`: exit `0`

No pre-existing porcelain row disappeared and no unexpected row appeared.
The production owner and terminal ledger were already dirty/untracked at intake,
so their porcelain state codes remained unchanged; only the two newly owned
artifact paths added rows. Existing neighboring QueryTransformer hunks were not
removed or reformatted as a whole file.
