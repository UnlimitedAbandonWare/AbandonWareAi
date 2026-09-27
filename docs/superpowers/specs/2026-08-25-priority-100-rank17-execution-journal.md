# Priority 100 rank 17 execution journal

## Outcome

- Production seam: `main/java/infra/cache/SingleFlightCache.java`
- Disposition change: rank `17`, `HOLD -> FIXED`
- Actual HOLD reduction: `1`
- Final terminal totals: `FIXED=20`, `NO_PATCH_NEEDED=31`, `HOLD=31`,
  `EVIDENCE_NEEDED=18`
- Next requested seam: shared ranks `19/93`, `QueryTransformer` interruption
  preservation and physical worker cancellation

No commit, staging, push, deployment, database change, provider call, protected
configuration edit, or `opnessl` mutation was performed.

## Fresh owner and preimage proof

- repository: `C:\AbandonWare\demo-1\demo-1\src`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- worktree: `C:/AbandonWare/demo-1/demo-1/src`
- Java: `17.0.13`
- `.git/index.lock` present: `false`
- Git/Git-LFS process count: `0`
- active source-edit lease count before preflight: `0`
- active top-level PatchDrop patch count: `0`
- pre-rank porcelain entries: `1693`
- pre-rank porcelain SHA-256:
  `CEDF6DAD1196971DEE216887324D41AB4AA4912C71C5F14FA7A490256CB5CC79`
- active sourceSet: root `main/java`; active test sourceSet: `src/test/java`
- Gradle module proof: root plus `:app`
- owner: public generic `infra.cache.SingleFlightCache`, compiled into the active
  root class output
- compiled class count: `1`
- internal production Java/resource caller count: `0`
- scope limitation: active compiled public API contract only; runtime
  invocation remains `not_observed`
- frozen production preimage SHA-256:
  `353F8188F30B597A045297F9173097596B1C24336658AE88FEF0FCB6AF35BDA3`
- pre-existing dirty production hunk: redacted TraceStore breadcrumbs and
  formatting; it was preserved outside the election delta
- existing test: `src/test/java/infra/cache/SingleFlightCacheTest.java`,
  untracked, SHA-256
  `40CB80D5172B98F88D86E124CCA553D6DC392B0817233D1BC7A79BEFB9158E75`
- existing untracked test edit authorization: `false`; it was not modified
- existing-test baseline: `1/1` GREEN
- owned focused test:
  `src/test/java/infra/cache/SingleFlightCacheElectionContractTest.java`
- owned focused test existed before the session: `false`
- owned focused test ignored by Git: `false`

## Frozen EvidenceSnapshot

- evidenceRowCount: `20`
- evidenceSnapshotHash:
  `D9F4383772841DA632E7146D08142CD16F9B06180FB7B9C7E47AAB1023598B6C`
- rawPromptStored: `false`

```text
E01|root=C:\AbandonWare\demo-1\demo-1\src
E02|branch=codex/owned-runtime-browser-restart;head=0796a3c5b29bbb08c3314bd40649d856d4a7bce6;worktree=desktop-root
E03|indexLockPresent=false;gitProcessCount=0;activeSourceLeases=0;topLevelPatchCount=0
E04|java=17.0.13;activeMain=main/java;activeTest=src/test/java;module=:app confirmed
E05|ownerPath=main/java/infra/cache/SingleFlightCache.java;tracked=true;sha256=353F8188F30B597A045297F9173097596B1C24336658AE88FEF0FCB6AF35BDA3;dirty=true
E06|ownerType=public generic compiled class;compiledClassCount=1;internalProductionCallerCount=0
E07|scopeClaim=active compiled public API contract only;runtimeInvocation=not_observed
E08|mechanism=computeIfAbsent returns one future but isDone does not identify mapping creator
E09|interleaving=owner blocks in loader;follower sees same incomplete future;both execute loader
E10|minimalRepair=putIfAbsent candidate winner is sole loader owner;followers await existing future
E11|cleanupInvariant=remove(key,ownedFuture) after completion;failure still completes exceptionally
E12|existingTest=src/test/java/infra/cache/SingleFlightCacheTest.java;tracked=false;sha256=40CB80D5172B98F88D86E124CCA553D6DC392B0817233D1BC7A79BEFB9158E75;editAllowed=false
E13|baselineExisting=PASS;tests=1;failures=0;errors=0;skipped=0
E14|newOwnedTest=src/test/java/infra/cache/SingleFlightCacheElectionContractTest.java;exists=false;ignored=false
E15|testDesign=two fixed-pool callers;owner latch;follower state-or-loader condition;literal call-count and result assertions
E16|workingComparison=package-local SingleFlightExecutor elects through one computeIfAbsent async supplier, but API/lifecycle differ;no reuse patch
E17|verification=focused new test,existing test,checkSourceSetHygiene,checkLangchain4jVersionPurity,full test
E18|declaredWrites=SingleFlightCache.java,SingleFlightCacheElectionContractTest.java,terminal-ledger.md,rank17-execution-journal.md;protectedKeysTouched=false
E19|porcelainEntries=1693;porcelainSha256=CEDF6DAD1196971DEE216887324D41AB4AA4912C71C5F14FA7A490256CB5CC79
E20|authority=userExplicitRank17Patch=true;commitStagePushDeployDbProvider=false
```

## Source-edit three-way preflight

- processMode: `single-agent-logical-roles`
- canonicalQueryCount: `3`
- scenario IDs in both packets: `S17-ELECT,S17-FAIL,S17-PRESERVE`
- scenarioCoverageEqual: `true`
- decisiveEvidenceEqual: `true`

POSITIVE_QUERY:

- `S17-ELECT`: when one same-key owner is blocked in its loader, a follower
  must await the shared future, execute no second loader, and receive the same
  literal owner value.
- `S17-FAIL`: the elected loader remains responsible for exceptional
  completion and the existing redacted failure breadcrumb behavior.
- `S17-PRESERVE`: an exact method hunk and new owned test must preserve the
  pre-existing trace changes and must not modify the existing untracked test.
- evidence IDs:
  `E03,E04,E05,E06,E07,E08,E09,E10,E11,E12,E13,E14,E15,E17,E20`.

NEGATIVE_QUERY:

- `S17-ELECT`: challenged the idea that `computeIfAbsent` already elects the
  loader. It elects only the future creator; every caller can still observe the
  same incomplete future and pass `!isDone()`.
- `S17-FAIL`: challenged whether identity-fenced removal could retain a failed
  entry; the winner completes the same candidate exceptionally before removing
  exactly that mapping, while the existing test checks the breadcrumb path.
- `S17-PRESERVE`: challenged runtime reachability and untracked-test ownership;
  the selected goal therefore makes no internal runtime-use claim and excludes
  the existing untracked test from writes.
- no evidence was acquired by the Negative role.

NEUTRAL_QUERY:

- forwardOrder: `[POSITIVE_QUERY,NEGATIVE_QUERY]`
- reverseOrder: `[NEGATIVE_QUERY,POSITIVE_QUERY]`
- forwardVerdict / reverseVerdict: `APPLY / APPLY`
- forward/reverse decisive evidence IDs:
  `E03,E04,E05,E06,E07,E08,E09,E10,E11,E12,E13,E14,E15,E17,E20`
- orderStable: `true`
- score inputs: evidence `0.92`, causal `0.98`, verification `0.95`, user
  value `0.75`, reversibility `0.98`, cost efficiency `0.95`, time fit `0.95`,
  blast radius `0.08`, ambiguity `0.18`, authority expansion `0.02`
- goalScore: `87.45`
- verdict: `APPLY`
- selected goal: fix single-loader election in the active compiled public API;
  make no runtime-invocation claim
- nextSingleProof: acquire the lease and require the competing-loader test to
  observe two loader calls against the frozen preimage
- nextWorkflow: `existing-source-owner-guard`

## Lease and deterministic RED

- lease topic: `priority100-rank17-single-flight-election`
- lease owner: `codex-rank17-20260825`
- TTL: `180` minutes
- lease acquisition result: `source-edit-locks=1`
- source SHA immediately before lease:
  `353F8188F30B597A045297F9173097596B1C24336658AE88FEF0FCB6AF35BDA3`
- focused test SHA-256:
  `F610E30BCD6B2A1A367CCCD1E81A182668B03F8BFDD27C6BCF9AE7FFE0DE4DFF`
- RED result: exit `1`; tests `1`, failures `1`, errors `0`, skipped `0`
- expected assertion: `only the elected caller may execute the loader`
- observed literal values: expected loader calls `1`, actual loader calls `2`

The first caller was held inside its loader. The follower obtained the same
incomplete future, passed the shared `!isDone()` check, and ran its own loader.
This directly proves that future creation was atomic but loader ownership was
not elected.

## Minimal owner-election patch

- each caller creates a private candidate `CompletableFuture`
- `inFlight.putIfAbsent(key,candidate)` elects the only loader owner
- only `existing == null` executes `loader.call()`
- followers await the existing future
- cleanup uses `inFlight.remove(key,candidate)` to avoid deleting any mapping
  other than the elected caller's entry
- existing redacted tracing and exception propagation were preserved
- final production SHA-256:
  `E699F9BEF191614E25999B1FCDF698330E39B58C31FE1585D1BCA0935ABA82AF`
- configuration, dependency, public API signature, DB, provider, and resource
  changes: `false`

## GREEN and regression proof

- focused election suite: `1/1` GREEN
- affected suites: existing tracing `1` plus election `1`, total `2/2` GREEN
- existing untracked test final SHA-256:
  `40CB80D5172B98F88D86E124CCA553D6DC392B0817233D1BC7A79BEFB9158E75`
- existing untracked test final SHA equals preimage: `true`
- `checkSourceSetHygiene`: `BUILD SUCCESSFUL`
- `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`; no version drift from
  `1.0.1`
- full command: `gradlew.bat test --rerun-tasks --no-daemon --console=plain`
  with split host `desktop-rank17`, isolated Gradle user home, and isolated
  project cache
- full result: `BUILD SUCCESSFUL in 3m 35s`
- XML aggregation: suites `1015`, tests `6483`, failures `0`, errors `0`,
  skipped `3`
- `git diff --check`: exit `0`; output consists of pre-existing line-ending
  warnings
- runtime invocation: `not_observed`

## Final preservation and lease release

- lease release UTC: `2026-08-25T01:17:29.6614089Z`
- lease release result: `source-edit-locks=0`
- post-release active/corrupt/expired lease counts: `0/0/0`
- `.git/index.lock` present: `false`
- final branch / HEAD:
  `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- final worktree: `C:/AbandonWare/demo-1/demo-1/src`
- final broad porcelain entries: `1694`
- intentional new broad-porcelain row: this journal
- broad porcelain entries excluding this journal: `1693`
- filtered porcelain SHA-256:
  `CEDF6DAD1196971DEE216887324D41AB4AA4912C71C5F14FA7A490256CB5CC79`
- filtered status equals the exact pre-rank status: `true`
- election test exact-path status: `??`
- election test exists in HEAD/index: `false/false`

The broad porcelain inventory already contained one collapsed untracked
`src/test/java/infra/cache/` directory row for the pre-existing test. The new
owned election test is represented by that same directory row, so it does not
add a second broad row; exact-path status proves it is a distinct new untracked
file, not a restored or overwritten tracked path.

- task delta reverse-reconstructed source SHA-256:
  `353F8188F30B597A045297F9173097596B1C24336658AE88FEF0FCB6AF35BDA3`
- reverse-reconstructed source equals the frozen preimage: `true`
- terminal-ledger rows / distinct ranks: `100/100`
- terminal-ledger SHA-256:
  `E615FA99923A2CC7B2729FD075EC0D20F402D876F87AB9D51F584D34C66AA020`
- terminal counts: `20/31/31/18`
- `git diff --check`: exit `0`, output lines `622`, output SHA-256
  `2B9C6D83D3BB86687C250804B2BD149C50C94164694020A5A9FD81A2924409D7`
- task-local trailing whitespace: `0`
- task-local secret-literal assignments: `0`
- task-local `opnessl` assignments: `0`

No existing broad porcelain row disappeared, no unexpected broad row appeared,
and the existing untracked tracing test remained byte-identical. The unrelated
redaction/tracing changes in the dirty production file remain outside the rank
17 delta.
