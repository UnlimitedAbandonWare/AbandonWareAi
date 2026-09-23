# Priority 100 rank 14/80 execution journal

## Stale index-lock recovery

### Before deletion

- checkedAtUtc: `2026-08-24T23:13:35.6974395Z`
- repository: `C:\AbandonWare\demo-1\demo-1\src`
- gitDir: `C:\AbandonWare\demo-1\demo-1\src\.git`
- exact target: `C:\AbandonWare\demo-1\demo-1\src\.git\index.lock`
- targetSizeBytes: `0`
- targetLastWriteTimeUtc: `2026-08-24T21:35:00.1499763Z`
- metadataMatchesApprovedRecord: `true`
- exclusiveReadWriteOpenSucceeded: `true`
- git.exe process count: `0`
- git-lfs.exe process count: `0`
- observed IDE process count: `0`
- IDE-spawned Git process count: `0`
- active IDE Git operation observed: `false`
- source-edit lease active count: `0`
- source-edit lease blocking count: `0`
- active top-level PatchDrop patch count: `0`
- pre-delete `git status --porcelain=v1` exit: `0`
- pre-delete status line count: `1689`
- pre-delete status SHA-256: `FAFB01E1782B6B91341E95C61DAFCDE9674F814A974BB5B59C6C1E4C4C1466F6`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- current worktree: `C:/AbandonWare/demo-1/demo-1/src`
- approved deletion preconditions satisfied: `true`

The IDE-operation observation is bounded to the Windows process inventory plus
an exclusive open of the exact lock file. No Git/Git-LFS process, IDE process,
IDE child Git process, or competing file handle was observed.

### After deletion

- final pre-delete recheckAtUtc: `2026-08-24T23:14:52.3725129Z`
- final pre-delete exact target: `C:\AbandonWare\demo-1\demo-1\src\.git\index.lock`
- final pre-delete sizeBytes: `0`
- final pre-delete lastWriteTimeUtc: `2026-08-24T21:35:00.1499763Z`
- final pre-delete git.exe process count: `0`
- final pre-delete git-lfs.exe process count: `0`
- final pre-delete observed IDE process count: `0`
- final pre-delete IDE child Git process count: `0`
- final pre-delete source-edit lease active/blocking counts: `0/0`
- final pre-delete exclusive open: `true`
- final pre-delete safe-to-delete result: `true`
- deletion scope: exact target above only; no other `.git` path was selected
- deletion window UTC: after `2026-08-24T23:14:52.3725129Z` and before
  `2026-08-24T23:15:26.8976616Z`
- post-delete target exists: `false`
- post-delete `git status --porcelain=v1` exit: `0`
- post-delete status line count: `1690`
- post-delete status SHA-256:
  `49B036A7C90D200D6A3146C2BDC276A0B07D1A8DE7ED1D454F6C61A79FECC9E6`
- intentional journal status line count: `1`
- post-delete status excluding this intentional journal line: `1689`
- status excluding journal SHA-256:
  `FAFB01E1782B6B91341E95C61DAFCDE9674F814A974BB5B59C6C1E4C4C1466F6`
- prior dirty state preserved byte-for-byte as porcelain output: `true`
- `git diff --check` exit: `0`
- `git diff --check` output lines: `622` pre-existing line-ending warnings,
  with no error exit
- `git diff --check` output SHA-256:
  `1B40590314A6998AA98553CF173D764462E12F6E0A18F3106608F94E51B2DAFE`
- branch after deletion: `codex/owned-runtime-browser-restart`
- HEAD after deletion: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- current worktree after deletion: `C:/AbandonWare/demo-1/demo-1/src`

The only new porcelain entry was this execution journal. Removing that one
intentional line reproduced the exact pre-delete status hash and line count, so
no existing modification disappeared and no other new worktree change was
introduced by lock recovery.

## Rank 14/80

### Frozen EvidenceSnapshot

- evidenceRowCount: `18`
- evidenceSummaryChars: `2057`
- evidenceSnapshotHash:
  `12455601877E40DBABD672D688EB65B813E4EECAA99782CEB014283C937E14C9`
- rawPromptStored: `false`

```text
E01|desktopRoot=C:\AbandonWare\demo-1\demo-1\src|rootVerified=true
E02|branch=codex/owned-runtime-browser-restart|head=0796a3c5b29bbb08c3314bd40649d856d4a7bce6
E03|indexLockPresent=false|sourceLeaseActive=0|sourceLeaseBlocking=0|topLevelPatchCount=0
E04|porcelainLineCount=1690|priorDirtyRowsPreserved=1689|priorDirtyStatusSha256=FAFB01E1782B6B91341E95C61DAFCDE9674F814A974BB5B59C6C1E4C4C1466F6
E05|javaMajor=17|javaVersionExit=0
E06|activeMain=main/java,main/resources|activeTest=src/test/java,src/test/resources|buildEvidence=build.gradle.kts:334-350
E07|activeOwner=main/java/com/example/lms/service/ChatWorkflow.java|serviceAnnotation=195|entryChain=ChatApiController->ChatService->ChatWorkflow
E08|sourceDirty=true|sourceSha256=DEFD994E7648C5302666A5B879F4ABE8C317563E15E6A85B56D51E3FBBA2B3CD|preimageFrozen=true
E09|seam=directLiteralCandidateBeforeMarker|range=9599-9607|mechanism=stripSuffixThenSafeCandidateWithoutGenericLabelGuard
E10|shortCircuitRange=1384-1389|publicContractRange=9298-9319|outcome=direct-literal-before-disambiguation
E11|acceptanceInputSha256=67DE792934AD2F772A9332F0AB8DC714F45793428EE68EFFEB04E542355655DA|rawPromptStored=false|failureHypothesis=generic-label-local-short-circuit
E12|plannedTest=src/test/java/com/example/lms/service/ChatWorkflowExactTokenRoutingTest.java|exists=false|activeSourceSet=true|newFileOwner=rank14-80-session
E13|existingRelatedTest=src/test/java/com/example/lms/service/ChatWorkflowAgentVisibleDebugEvidenceTest.java|tracked=false|editAllowed=false
E14|dirtyIsolation=exact-method-hunk|userAuthorizedRank=14-80|preserveUnrelatedHunks=true|compareAndSwap=sourceSha256
E15|verification=focusedTest,affectedDirectLiteralSuite,checkSourceSetHygiene,checkLangchain4jVersionPurity,diffCheck|commandsKnown=true
E16|protectedConfigTouched=false|opnesslTouched=false|dependencyChange=false|publicApiChange=false
E17|decisionDependsOnSupabase=false|providerCallAllowed=false|runtimeLineage=not_observed
E18|reviewPack=demo1_three_perspective_chat_postprocess|manifestRegistered=true|canonicalQueryCount=3
```

### Source-edit three-way preflight

- processMode: `single-agent-logical-roles`
- canonicalQueryCount: `3`
- positivePacketChars: `2232` (limit `2400`)
- negativePacketChars: `1885` (limit `2400`)
- neutralPacketChars: `1345` (limit `1800`)
- scenario IDs in both packets: `R14-S1,R14-S2,R14-S3`
- scenarioCoverageEqual: `true`
- decisiveEvidenceEqual: `true`

POSITIVE_QUERY:

- candidateGoal: reject a generic bare output label from the local
  direct-literal route while preserving explicit literals and machine tokens.
- `R14-S1`: suffix stripping can turn a generic ASCII label into a broad safe
  candidate and short-circuit before disambiguation. Falsifier: both public
  calls already reject the hashed input.
- `R14-S2`: a contextual guard can remain narrower than all alphabetic
  literals. Falsifier: either `READY` or `RREC-302` changes result.
- `R14-S3`: frozen SHA, exact hunk, new owned test, and the lease can isolate
  the dirty owner. Falsifier: changed preimage, lease collision, or extra diff.
- evidence IDs: `E03,E06,E07,E08,E09,E10,E11,E12,E14,E15,E16`.

NEGATIVE_QUERY:

- `R14-S1`: the input could intend the literal word, or attribution rather
  than extraction could be wrong; therefore reject a blanket ASCII rule and
  require the same-input RED.
- `R14-S2`: `READY` shares the suffix shape and global directive-token changes
  can reject explicit values; therefore retain alphabetic and punctuated
  positive controls.
- `R14-S3`: the 13k-line file is heavily dirty; therefore require the E08 SHA
  immediately before mutation and stop on lease/preimage change.
- no extra evidence was acquired by this role.

NEUTRAL_QUERY:

- forwardOrder: `[POSITIVE_QUERY,NEGATIVE_QUERY]`
- reverseOrder: `[NEGATIVE_QUERY,POSITIVE_QUERY]`
- forwardVerdict: `APPLY`
- reverseVerdict: `APPLY`
- forward/reverse decisive evidence IDs:
  `E03,E06,E07,E08,E09,E10,E11,E12,E14,E15,E16`
- orderStable: `true`
- score inputs: evidence `0.85`, causal `0.90`, verification `0.95`, user
  value `0.85`, reversibility `0.95`, cost efficiency `0.90`, time fit `0.90`,
  blast radius `0.15`, ambiguity `0.20`, authority expansion `0.00`
- goalScore: `83.25`
- verdict: `APPLY`
- selected goal: create the focused RED first; only on the expected RED, add a
  contextual before-marker generic-label guard with positive controls.
- rejected claims: no all-ASCII rule, global directive-token expansion,
  browser/provider/wire success, statistical uplift, or runtime lineage.
- nextSingleProof: run the new focused test against E08 and require the generic
  label assertion to fail with the existing non-null literal.
- nextWorkflow: `existing-source-owner-guard`

### Lease and RED

- lease topic: `priority100-rank14-80-exact-token`
- lease owner: `codex-rank14-80-20260825`
- lease result: `source-edit-locks=1`
- source SHA immediately before lease:
  `DEFD994E7648C5302666A5B879F4ABE8C317563E15E6A85B56D51E3FBBA2B3CD`
- owned test:
  `src/test/java/com/example/lms/service/ChatWorkflowExactTokenRoutingTest.java`
- test SHA-256:
  `50CF8B22B9B8CA8C3B1727DE6DCBFFC07B22DE70E2D2B7FF696854578CE64265`
- RED command: `gradlew.bat test --tests
  com.example.lms.service.ChatWorkflowExactTokenRoutingTest --no-daemon
  --console=plain` with isolated user/project caches and build host
  `desktop-rank14`
- RED result: exit `1`, tests `7`, failures `4`, errors `0`, skipped `0`
- expected failures: bare generic labels `TOKEN`, `CODE`, `VALUE`, `WORD`
  each classified as a direct literal; the acceptance case observed
  classifier `true` and fallback `TOKEN`
- passing controls: alphabetic value `READY`, explicitly named `TOKEN`, and
  punctuated machine token `GPU3060-PROBE`
- RED XML SHA-256:
  `D39E32B4C9CB0739328D7A955C557E66459CFFEAB9E9CCC49A7E789270FB3CD8`

### Final implementation and verification

- production file:
  `main/java/com/example/lms/service/ChatWorkflow.java`
- frozen preimage SHA-256:
  `DEFD994E7648C5302666A5B879F4ABE8C317563E15E6A85B56D51E3FBBA2B3CD`
- final source SHA-256:
  `31B96FBD0D6093E72CE297E2AEEE4B901042B56030DF12071E167BB9464BD0AD`
- production change: add semantic labels `token/code/value/word`; reject them
  only when the before-marker raw candidate is a bare ASCII word followed by
  Korean `만` and suffix stripping would otherwise promote the label to a
  direct literal.
- preserved routes: alphabetic literal value, explicitly named literal, and
  punctuated/digit-bearing machine token.
- task delta SHA-256:
  `D674B5BB8ABB907915F8F79C6C64C471955432EF5411B6FE7D60AEF869C0FFA3`
- reverse-delta reconstructed preimage SHA-256:
  `DEFD994E7648C5302666A5B879F4ABE8C317563E15E6A85B56D51E3FBBA2B3CD`
- reconstructed preimage equals frozen E08: `true`

The first test filename contained `token` and was ignored by the existing
`.gitignore:133` pattern `/**/*token*.*`. It was an artifact created in this
session, so it was removed and its unchanged behavioral content was moved to
the non-ignored final path below. No user-owned untracked file was overwritten.

- final regression file:
  `src/test/java/com/example/lms/service/ChatWorkflowLiteralRoutingRegressionTest.java`
- final test SHA-256:
  `7D17AD01C16991222AA337793D0A26687649377E782451BC6885728B49B98713`
- `git check-ignore --no-index` exit for final test: `1` (not ignored)
- porcelain entry for final test: `??`
- final focused/affected result: `BUILD SUCCESSFUL`; new tests `7/7`, plus
  existing `ChatWorkflowAgentVisibleDebugEvidenceTest.directLiteral*`
- final full `test`: `BUILD SUCCESSFUL in 2m 33s`
- final full XML aggregation: suites `1013`, tests `6480`, failures `0`,
  errors `0`, skipped `3`
- new focused XML: tests `7`, failures `0`, SHA-256
  `9373C849DAA9EF3C22A0C33037AB6FA9EB2219FB35EDBD08F5B7037B1D4899A8`
- stale ignored test XML/class after final run: `false/false`
- final `checkSourceSetHygiene`: `BUILD SUCCESSFUL`
- final `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`
- `git diff --check`: exit `0`; only the existing `622` line-ending warnings
- task-local trailing whitespace count: `0`
- task-local secret-like value count: `0`
- task-local `opnessl` assignment count: `0`
- dependency/resource/public API/DB/provider change: `false`
- browser/provider/wire runtime execution: `not_observed`

### Ledger effect

- terminal ledger final SHA-256:
  `E47DA71264ECF45C36D59B3CFEA2B798E10A64C50337B532971B9ED6694E331F`
- rows/distinct ranks: `100/100`
- dispositions: `FIXED=17`, `NO_PATCH_NEEDED=31`, `HOLD=34`,
  `EVIDENCE_NEEDED=18`
- malformed HOLD rows: `0`
- shared seam updated once and linked to ranks: `14,80`
- actual HOLD reduction in this session: `2`
- next requested ranks: `15/16` federated stored-ID and null-segment seam

### Lease release and post-state

- lease release: `source-edit-locks=0`
- post-release checkedAtUtc: `2026-08-24T23:43:55.2182699Z`
- source-edit lease active/blocking counts: `0/0`
- `.git/index.lock` present: `false`
- final `git status --porcelain=v1` exit/line count: `0/1691`
- intentional new paths excluded from comparison: this journal and
  `ChatWorkflowLiteralRoutingRegressionTest.java`
- filtered final status line count: `1689`
- filtered final status SHA-256:
  `FAFB01E1782B6B91341E95C61DAFCDE9674F814A974BB5B59C6C1E4C4C1466F6`
- filtered status equals the pre-deletion dirty-path baseline: `true`
- final `git diff --check` exit/line count: `0/622`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- worktree: `C:/AbandonWare/demo-1/demo-1/src`

Rank 14/80 is complete for the source/test routing contract. No commit,
staging, push, deployment, database change, provider call, or protected-key
mutation was performed.
