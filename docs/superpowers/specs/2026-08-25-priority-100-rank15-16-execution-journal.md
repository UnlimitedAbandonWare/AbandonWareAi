# Priority 100 rank 15/16 execution journal

## Outcome

- Shared production seam: `main/java/com/example/lms/vector/FederatedEmbeddingStore.java`
- Ranks closed by the same patch: `15,16`
- Final disposition change: `HOLD -> FIXED` for both ranks
- Actual HOLD reduction: `2`
- Final terminal totals: `FIXED=19`, `NO_PATCH_NEEDED=31`, `HOLD=32`,
  `EVIDENCE_NEEDED=18`
- Next requested rank: `17`, `SingleFlightCache` single-loader election

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
- pre-rank porcelain entries: `1691`
- pre-rank porcelain SHA-256:
  `4C42FE9E9D5D8FC6A142F84198E24A71D9B198B321598352CE100D893D3131AB`
- active sourceSet: root `main/java`; active test sourceSet: `src/test/java`
- Gradle module proof: root plus `:app`; baseline focused test: `9/9`
- active owner: `com.example.lms.vector.FederatedEmbeddingStore`, which is a
  Spring `@Component`, `@Primary`, and `EmbeddingStore<TextSegment>`
- active write caller: `VectorQuarantineDlqService`, which injects
  `@Qualifier("federatedEmbeddingStore")` and calls the explicit-ID `addAll`
  contract during DLQ redrive
- excluded same-name class: `com.abandonware.ai.vector.FederatedEmbeddingStore`
  has a separate `put/search` API and is not the LangChain4j write-contract owner
- frozen production preimage SHA-256:
  `191FFF695B2B3725EB322788CE79D4DC4AEB3F6CFCF5F251ABAE1F8868ACB835`
- pre-existing dirty production hunk: search interruption preservation only;
  it lies outside the single-entry write methods and was preserved
- pre-existing dirty test hunk: interruption coverage in tracked
  `FederatedEmbeddingStoreTest`; it was not modified
- owned focused test path:
  `src/test/java/com/example/lms/vector/FederatedEmbeddingStoreWriteContractTest.java`
- focused test existed before this session: `false`
- focused test ignored by Git: `false`

## Frozen EvidenceSnapshot

- evidenceRowCount: `20`
- evidenceSnapshotHash:
  `4AE6856055AEA24D47EE333FB6BE43351E0EABF0BE19080B05EBF4A9CCCD31B2`
- rawPromptStored: `false`

```text
E01|root=C:\AbandonWare\demo-1\demo-1\src
E02|branch=codex/owned-runtime-browser-restart;head=0796a3c5b29bbb08c3314bd40649d856d4a7bce6;singleTargetWorktree=true
E03|indexLockPresent=false;gitProcessCount=0
E04|activeSourceLeases=0;topLevelPatchCount=0
E05|java=17.0.13
E06|activeSourceSet=main/java;activeTestSourceSet=src/test/java;module=:app confirmed
E07|ownerPath=main/java/com/example/lms/vector/FederatedEmbeddingStore.java;tracked=true;sha256=191FFF695B2B3725EB322788CE79D4DC4AEB3F6CFCF5F251ABAE1F8868ACB835;dirty=true
E08|ownerType=@Component+@Primary+EmbeddingStore<TextSegment>;bean=federatedEmbeddingStore
E09|activeCaller=VectorQuarantineDlqService;qualifier=federatedEmbeddingStore;write=addAll(explicitId,embedding,segment)
E10|sameNameAlternate=com.abandonware.ai.vector.FederatedEmbeddingStore;API=put/search;excludedFromDeclaredTargets
E11|defect15=add(Embedding) creates id then invokes overload that creates another id before upstream addAll
E12|defect16=add(String,Embedding) routes null segment into List.of(segment), which rejects null before fanout
E13|workingPattern=addAll(embeddings) already uses Collections.nCopies(size,null) for null-tolerant segment list
E14|existingTest=src/test/java/com/example/lms/vector/FederatedEmbeddingStoreTest.java;tracked=true;sha256=94758631F5AE3096597462CD0D4D26759EA490FEB4C515E1D61B5C44A7CAE93F;dirtyOnlyExistingInterruptCoverage=true
E15|newFocusedTest=src/test/java/com/example/lms/vector/FederatedEmbeddingStoreWriteContractTest.java;exists=false;ignored=false
E16|baselineFocused=PASS;tests=9;failures=0;errors=0;skipped=0
E17|verification=focused test, affected FederatedEmbeddingStoreTest, checkSourceSetHygiene, checkLangchain4jVersionPurity, full test
E18|declaredWrites=FederatedEmbeddingStore.java,FederatedEmbeddingStoreWriteContractTest.java,terminal-ledger.md,rank15-16-execution-journal.md;protectedKeysTouched=false
E19|porcelainEntries=1691;porcelainSha256=4C42FE9E9D5D8FC6A142F84198E24A71D9B198B321598352CE100D893D3131AB
E20|authority=userExplicitPatchRanks15And16=true;commitStagePushDeployDbProvider=false
```

## Source-edit three-way preflight

- processMode: `single-agent-logical-roles`
- canonicalQueryCount: `3`
- scenario IDs in both packets: `S15-ID,S16-NULL,S-PRESERVE`
- scenarioCoverageEqual: `true`
- decisiveEvidenceEqual: `true`

POSITIVE_QUERY:

- `S15-ID`: `add(Embedding)` must return the exact identifier stored by the
  upstream store. The expected observation is one real in-memory match whose
  `embeddingId` equals the returned value.
- `S16-NULL`: the explicit-ID/no-segment overload must fan out one null segment
  without a container-construction exception and must retain the supplied ID.
- `S-PRESERVE`: an exact write-method hunk and a new owned test must leave the
  pre-existing search-interrupt source/test hunks unchanged.
- decisive evidence: `E03,E06,E07,E08,E09,E11,E12,E13,E15,E16,E17,E20`.

NEGATIVE_QUERY:

- `S15-ID`: challenged the possibility that the returned UUID was intentionally
  synthetic; the `EmbeddingStore` boundary and real upstream match make that
  interpretation falsifiable.
- `S16-NULL`: challenged upstream null support; the existing `nCopies(...,null)`
  path and the no-segment API identify null as the established absence value.
- `S-PRESERVE`: challenged dirty-target ownership and same-name bean ambiguity;
  the explicit qualified caller, frozen SHA, zero lease/lock state, excluded
  alternate API, and exact declared targets bound the patch.
- no evidence was acquired by the Negative role.

NEUTRAL_QUERY:

- forwardOrder: `[POSITIVE_QUERY,NEGATIVE_QUERY]`
- reverseOrder: `[NEGATIVE_QUERY,POSITIVE_QUERY]`
- forwardVerdict / reverseVerdict: `APPLY / APPLY`
- forward/reverse decisive evidence IDs:
  `E03,E06,E07,E08,E09,E11,E12,E13,E15,E16,E17,E20`
- orderStable: `true`
- score inputs: evidence `0.96`, causal `0.98`, verification `0.95`, user
  value `0.92`, reversibility `0.99`, cost efficiency `0.97`, time fit `0.97`,
  blast radius `0.12`, ambiguity `0.08`, authority expansion `0.02`
- goalScore: `92.10`
- verdict: `APPLY`
- nextSingleProof: revalidate the frozen source SHA, acquire the lease, and run
  the two focused tests against the unchanged implementation
- nextWorkflow: `existing-source-owner-guard`

## Lease and RED

- lease topic: `priority100-rank15-16-federated-write-contract`
- lease owner: `codex-rank15-16-20260825`
- TTL: `180` minutes
- lease acquisition result: `source-edit-locks=1`
- source SHA immediately before lease:
  `191FFF695B2B3725EB322788CE79D4DC4AEB3F6CFCF5F251ABAE1F8868ACB835`
- focused test SHA-256:
  `7CC1800D9258BF67C0DE9BC40B439512F90029F0FE6AC03369B84C52DE8B2A6B`
- RED result: exit `1`; tests `2`, failures `2`
- rank 15 RED observation: the old `add(Embedding)` failed before the ID
  comparison because `TextSegment.from("")` raised
  `IllegalArgumentException: text cannot be null or blank`
- rank 16 RED observation: the explicit-ID/no-segment call failed under
  `assertDoesNotThrow` because `List.of(null)` raised `NullPointerException`

The rank 15 live failure precedes the previously identified double-UUID
comparison, but it has the same root cause: the generated-ID overload delegates
through the wrong overload instead of the explicit-ID single-entry fan-out.
The regression still detects a future double-ID mutation because, once the
invalid empty segment is removed, an independently generated storage ID differs
from the real upstream match ID.

## Minimal shared-seam patch

- `add(Embedding)` now generates one UUID and passes it to the private
  explicit-ID helper with an absent segment.
- `add(Embedding, TextSegment)` uses that same helper, so a caller-supplied null
  segment follows the same safe path.
- the private helper uses `Collections.singletonList(segment)`, which permits
  the established null absence value, instead of `List.of(segment)`.
- the now-unused `Metadata` import and invalid blank-segment construction were
  removed.
- final production SHA-256:
  `516440D8CCBA19BACE10EA817C2C1D8AE96081128EE8E938EF37ACFC45553B85`
- configuration, dependency, public API signature, DB, provider, and resource
  changes: `false`

## GREEN and regression proof

- focused new suite: `2` tests, `0` failures, `0` errors, `0` skipped
- affected suites: existing `9` plus new `2`, total `11/11` GREEN
- `checkSourceSetHygiene`: `BUILD SUCCESSFUL`; active-source collision failure
  did not occur
- `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`; no version drift from
  `1.0.1`
- full command: `gradlew.bat test --rerun-tasks --no-daemon --console=plain`
  with split host `desktop-rank15`, isolated Gradle user home, and isolated
  project cache
- full result: `BUILD SUCCESSFUL in 3m 43s`
- XML aggregation: suites `1014`, tests `6482`, failures `0`, errors `0`,
  skipped `3`
- `git diff --check`: exit `0`; output consists of pre-existing line-ending
  warnings
- browser/provider/wire runtime execution: `not_observed`

## Final preservation and lease release

- lease release UTC: `2026-08-25T00:17:27.7806448Z`
- lease release result: `source-edit-locks=0`
- post-release active/corrupt/expired lease counts: `0/0/0`
- `.git/index.lock` present: `false`
- final branch / HEAD:
  `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- final worktree: `C:/AbandonWare/demo-1/demo-1/src`
- final porcelain entries: `1693`
- intentional new paths: this journal and
  `FederatedEmbeddingStoreWriteContractTest.java`
- porcelain entries excluding those two paths: `1691`
- filtered porcelain SHA-256:
  `4C42FE9E9D5D8FC6A142F84198E24A71D9B198B321598352CE100D893D3131AB`
- filtered status equals the exact pre-rank status: `true`
- task delta reverse-reconstructed source SHA-256:
  `191FFF695B2B3725EB322788CE79D4DC4AEB3F6CFCF5F251ABAE1F8868ACB835`
- reverse-reconstructed source equals the frozen preimage: `true`
- terminal-ledger rows / distinct ranks: `100/100`
- terminal-ledger SHA-256:
  `D63D4A16C9593EB376D8CACD3FD9C15661926AEDA54947C9563CDE525E3E357D`
- terminal counts: `19/31/32/18`
- `git diff --check`: exit `0`, output lines `622`, output SHA-256
  `2B9C6D83D3BB86687C250804B2BD149C50C94164694020A5A9FD81A2924409D7`
- task-local trailing whitespace: `0`
- task-local secret-literal assignments: `0`
- task-local `opnessl` assignments: `0`

No existing porcelain path disappeared, and no path other than the two owned
new artifacts appeared. The unrelated search-interrupt change in the dirty
production file and its existing tracked test changes remain outside the rank
15/16 delta.
