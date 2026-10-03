# Kit D — F05, F06, F11, F12 conditional skip

Run the probe before any of these patches. The probe only reads. It does not
enable Neo4j, the graph-rag profile, BM25, or incremental publish.

```powershell
python -B scripts/max_push_skip_inactive.py --root .
```

| Id | SKIP_INACTIVE means | ACTIVE means Codex may patch |
|---|---|---|
| F05 | Observed Spring profiles do not include `graph-rag`. The indexing default inside `application-graph-rag.yml` is not the dev runtime. | Profile list includes `graph-rag`. |
| F06 | `retrieval.kg.neo4j.enabled` defaults off and manual-learning defaults off. | A live process flag is on, or a URI is present. Still do not enable it from this kit. |
| F11 | `ChatApiController` still cuts a finished answer into 60-character pieces and has no incremental-release symbol. | That symbol exists on a path the product already allows. |
| F12 | No component or bean factory for `com.example.lms.service.service.rag.bm25.Bm25Index`. An optional field is not activation. Do not register a bean. | The class is a component, or a `@Bean` factory for it exists. |

`EVIDENCE_NEEDED` is not a pass and not a reason to turn the feature on.

Live read on 2026-09-28, dev profile `local,meta-display` from `var/rag-launcher/LATEST.json`: F05, F06, and F11 were `SKIP_INACTIVE`. F12 listed `UnifiedRagOrchestrator.java` as an optional field (`required = false`) and the directive class has no component stereotype, so the probe stays `SKIP_INACTIVE` and must not register a bean. Re-run the script before a later patch. The abandonware `Bm25Index` is a different type.
`com.abandonware.ai.agent` BM25 types are a different class. Do not retarget F12 onto them.

F06 Exa, only when the probe says the Neo4j path is already active: official
Neo4j Java driver documentation only. While the verdict is `SKIP_INACTIVE`,
do not open Exa and do not start Neo4j.

Proposed tests if a row is active (directive names, not live classes yet):

- F05: `MixedProtectedBatchDoesNotForcePublicEntriesToSingleEmbeddingTest`, `ProtectedEmbeddingBatchPreservesIdVectorAlignmentTest`, `DeletedSourceBetweenEmbeddingAndStoreIsRejectedTest`, `PartialStoreFailureOnlyMarksCommittedIdsTest`, `CrossOwnerEntriesNeverShareCommitFenceTest`
- F06: `Neo4jWriterUsesOneManagedTransactionPerBatchTest`, `Neo4jTransactionRetryDoesNotDoubleCountTest`, `FailedBatchDoesNotReportUncommittedItemsTest`, `BatchedCypherPreservesSourceScopeAndMergeKeysTest`, `ConcurrentSourceDeletionNeverReappearsInGraphTest`
- F11: `GlobalValidationPathNeverPublishesRawDeltaTest`, `SlowClientCannotGrowUnboundedTokenQueueTest`, `ReconnectDoesNotCreateSecondGenerationTest`, `StreamingPersistsOneFinalTranscriptTest`, `CancelledRunDoesNotEmitIntoNewRunTest`, `UnsupportedProviderKeepsChosenModelBufferedTest`
- F12: only after a real BM25 caller is shown. The directive's target is `Bm25Index.put` rebuilding document frequency on every insert.

Original files if active: `VectorStoreService.java`, `GeneralGraphVectorGate.java`, `Neo4jKgChunkWriter.java`, `GraphRagChunkingService.java`, `GeneralGraphSourceAuthority.java`, `ChatApiController.java`, `Bm25Index.java` under `service/service/rag/bm25/`.
