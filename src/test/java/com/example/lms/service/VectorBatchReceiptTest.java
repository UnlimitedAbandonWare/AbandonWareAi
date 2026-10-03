package com.example.lms.service;

import com.example.lms.service.vector.VectorSidService;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.ingest.TrainRagIngestService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorBatchReceiptTest {
    @TempDir Path temp;
    @Test void anotherBatchOrEmptySharedFlushCannotConfirmThisOriginalRecord() throws Exception {
        var store=mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorStoreService.VectorFlushOutcome(true,1,0,"complete"));
        Path jsonl=temp.resolve("fixture.jsonl"), state=temp.resolve("state.json");
        Files.writeString(jsonl,"{\"question\":\"fixture\",\"answer\":\"fixture\",\"sessionId\":\"fixture\"}\n");
        var props=new UawAutolearnProperties(); props.getRetrain().setIngestStatePath(state.toString());
        var outcome=new TrainRagIngestService(store,mock(VectorSidService.class),props)
                .ingestNewSamplesDetailed(jsonl,"fixture",()->false);
        assertEquals(0,outcome.checkpointConfirmedDocs());
        assertFalse(outcome.complete()); assertFalse(Files.exists(state));
    }
    @Test void automaticFlushReceiptSurvivesAnEmptyFinalFlush() throws Exception {
        var service=service(); ReflectionTestUtils.setField(service,"batchSize",1);
        Object receipt=enqueue(service,"original","fixture","payload",Map.of());
        assertTrue((boolean)value(receipt,"durable"));
        assertEquals("empty",service.flush().reasonCode());
        assertTrue((boolean)value(receipt,"durable"));
    }
    @Test void storageAfterEnqueueConfirmsOnlyTheEnrolledRecord() throws Exception {
        var service=service(); service.enqueue("unrelated","other","payload",Map.of()); service.flush();
        Object receipt=enqueue(service,"original","fixture","payload",Map.of());
        assertFalse((boolean)value(receipt,"durable")); service.flush();
        assertTrue((boolean)value(receipt,"durable"));
    }
    @Test void blankInputRejectionCannotBecomeDurableEmptySuccess() throws Exception {
        var service=service(); Object receipt=enqueue(service,"original","fixture"," ",Map.of());
        assertEquals("empty",service.flush().reasonCode());
        assertFalse((boolean)value(receipt,"durable"));
        assertEquals("source_rejected",value(receipt,"reasonCode"));
    }
    @Test void storedButLostResponseIsUnknownCommit() throws Exception {
        var service=service();
        @SuppressWarnings("unchecked") var store=(EmbeddingStore<dev.langchain4j.data.segment.TextSegment>)ReflectionTestUtils.getField(service,"embeddingStore");
        Set<String> persisted = new HashSet<>();
        doAnswer(a -> { persisted.addAll(a.getArgument(0)); throw new RuntimeException(new java.util.concurrent.TimeoutException("synthetic")); })
                .when(store).addAll(anyList(),anyList(),anyList());
        Object receipt=enqueue(service,"original","fixture","payload",Map.of()); service.flush();
        assertFalse((boolean)value(receipt,"durable")); assertEquals("UNKNOWN_COMMIT",value(receipt,"reasonCode"));
        assertEquals(Set.of("original"), persisted);
    }
    @Test void aRejectedDerivedChunkCannotBeHiddenByItsStoredSibling() {
        var service=service(); ReflectionTestUtils.setField(service,"batchSize",1);
        chunks(service, "payload", " ");
        var receipt=service.enqueueWithReceipt("original","fixture","original payload",Map.of());
        assertFalse(receipt.durable());
        assertEquals("source_rejected",receipt.reasonCode());
        assertEquals(1,receipt.storedTargetCount());
    }
    @Test void allDerivedChunkIdsAreRequiredBeforeTheOriginalIsDurable() {
        var service=service(); chunks(service,"first payload","second payload");
        var receipt=service.enqueueWithReceipt("original","fixture","original payload",Map.of());
        assertEquals(Set.of("original#0","original#1"),receipt.targets().keySet().stream().map(VectorStoreService.ReceiptTarget::id).collect(java.util.stream.Collectors.toSet()));
        assertFalse(receipt.durable()); service.flush();
        assertTrue(receipt.durable()); assertEquals(2,receipt.storedTargetCount());
    }
    @Test void partialDerivedStorageDoesNotConfirmTheOriginal() {
        var service=service(); chunks(service,"first payload","second payload");
        ReflectionTestUtils.setField(service,"batchSize",1);
        @SuppressWarnings("unchecked") var store=(EmbeddingStore<dev.langchain4j.data.segment.TextSegment>)ReflectionTestUtils.getField(service,"embeddingStore");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(a -> { if(calls.incrementAndGet()==2) throw new IllegalStateException("synthetic"); return null; }).when(store).addAll(anyList(),anyList(),anyList());
        var receipt=service.enqueueWithReceipt("original","fixture","original payload",Map.of());
        assertFalse(receipt.durable()); assertEquals(1,receipt.storedTargetCount()); assertEquals(2,receipt.targets().size());
    }
    @Test void derivedShadowIdIncludesRunIdentityAndQuarantineIsCountedSeparately() {
        var shadow=service();
        ReflectionTestUtils.setField(shadow,"shadowWriteEnabled",true);
        ReflectionTestUtils.setField(shadow,"vectorShadowMergeDlqService",mock(com.example.lms.service.vector.VectorShadowMergeDlqService.class));
        try {
            org.slf4j.MDC.put("trace","first-fixture");
            var first=shadow.enqueueWithReceipt("original","fixture","payload",Map.of(VectorMetaKeys.META_SHADOW_WRITE,true));
            org.slf4j.MDC.put("trace","second-fixture");
            var second=shadow.enqueueWithReceipt("original","fixture","payload",Map.of(VectorMetaKeys.META_SHADOW_WRITE,true));
            shadow.flush();
            var a=first.targets().keySet().iterator().next(); var b=second.targets().keySet().iterator().next();
            assertTrue(first.durable()); assertTrue(second.durable()); assertNotEquals(a.id(),b.id());
            assertEquals("shadow",a.route()); assertNotEquals("original",a.id());
        } finally { org.slf4j.MDC.clear(); }
        var quarantine=service(); var guard=mock(com.example.lms.service.guard.VectorPoisonGuard.class);
        when(guard.inspectIngest(anyString(),anyString(),anyMap(),anyString())).thenReturn(new com.example.lms.service.guard.VectorPoisonGuard.IngestDecision(false,"payload",Map.of(),"synthetic",1));
        ReflectionTestUtils.setField(quarantine,"vectorPoisonGuard",guard);
        ReflectionTestUtils.setField(quarantine,"quarantineRewriteStableId",true);
        var receipt=quarantine.enqueueWithReceipt("original","fixture","payload",Map.of()); quarantine.flush();
        var target=receipt.targets().keySet().iterator().next();
        assertEquals("quarantine",target.route()); assertEquals("Q",target.sessionScope());
        assertNotEquals("original",target.id()); assertEquals(1,receipt.quarantinedTargetCount());
    }
    @Test void facadeNamespaceIsExplicitlyUnobservedRatherThanAnInventedDefault() {
        var service=service(); var receipt=service.enqueueWithReceipt("original","fixture","payload",Map.of());
        assertEquals("not_observed",receipt.targets().keySet().iterator().next().namespace());
    }
    @Test void directAdapterNamespaceAndLogicalScopeRemainDistinct() {
        var service=service(); var store=mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        when(store.namespace()).thenReturn("fixture-physical");
        ReflectionTestUtils.setField(service,"embeddingStore",store);
        var receipt=service.enqueueWithReceipt("original","fixture-logical","payload",Map.of());
        var target=receipt.targets().keySet().iterator().next();
        assertEquals("fixture-physical",target.namespace()); assertEquals("fixture-logical",target.sessionScope());
    }
    @Test void unknownCommitAfterActualFakeStorageCannotAdvanceTheCheckpoint() throws Exception {
        var service=service();
        @SuppressWarnings("unchecked") var store=(EmbeddingStore<dev.langchain4j.data.segment.TextSegment>)ReflectionTestUtils.getField(service,"embeddingStore");
        Set<String> persisted=new HashSet<>();
        doAnswer(a -> { persisted.addAll(a.getArgument(0)); throw new java.net.SocketTimeoutException("synthetic"); }).when(store).addAll(anyList(),anyList(),anyList());
        Path jsonl=temp.resolve("timeout.jsonl"), state=temp.resolve("timeout-state.json");
        Files.writeString(jsonl,"{\"question\":\"fixture\",\"answer\":\"fixture\",\"sessionId\":\"fixture\"}\n");
        var props=new UawAutolearnProperties(); props.getRetrain().setIngestStatePath(state.toString());
        var result=new TrainRagIngestService(service,mock(VectorSidService.class),props).ingestNewSamplesDetailed(jsonl,"fixture",()->false);
        assertEquals(1,persisted.size()); assertEquals("UNKNOWN_COMMIT",result.reasonCode());
        assertEquals(0,result.checkpointCommittedCount()); assertFalse(Files.exists(state));
    }
    @Test void quarantinedOriginalIsExcludedFromNormalStorageButCommitsItsCheckpoint() throws Exception {
        var service=quarantineService();
        Path jsonl=dataset("quarantine", "fixture-q"), state=temp.resolve("quarantine-state.json");
        var result=ingest(service,jsonl,state,"METADATA_ONLY");
        assertEquals(0,result.storedRecordCount());
        assertEquals(1,value(result,"policyExcludedRecordCount"));
        assertEquals(1,result.checkpointCommittedCount());
        assertEquals(Files.size(jsonl),result.checkpointOffset());
        assertEquals(0,result.unconfirmedDocs()); assertEquals("POLICY_EXCLUDED",result.status());
    }
    @Test void mixedNormalAndQuarantinedOriginalsHaveSeparateCounts() throws Exception {
        var service=quarantineService();
        var result=ingest(service,dataset("mixed","fixture-q","fixture-n"),temp.resolve("mixed-state.json"),"METADATA_ONLY");
        assertEquals(1,result.storedRecordCount());
        assertEquals(1,value(result,"policyExcludedRecordCount"));
        assertEquals(2,result.checkpointCommittedCount());
        assertEquals(0,result.unconfirmedDocs()); assertTrue(result.complete());
    }
    @Test void quarantineCheckpointFailureRetainsExcludedCountWithoutCommit() throws Exception {
        var service=quarantineService(); Path blocked=temp.resolve("blocked"); Files.writeString(blocked,"fixture");
        var result=ingest(service,dataset("blocked","fixture-q"),blocked.resolve("state.json"),"METADATA_ONLY");
        assertEquals(0,result.storedRecordCount());
        assertEquals(1,value(result,"policyExcludedRecordCount"));
        assertEquals(0,result.checkpointCommittedCount()); assertEquals(1,result.unconfirmedDocs());
        assertEquals("CHECKPOINT_FAILURE",result.status());
    }
    @Test void projectionNoneIsARecordedPolicyExclusionRatherThanAnEmptyRun() throws Exception {
        var result=ingest(service(),dataset("none","fixture-n"),temp.resolve("none-state.json"),"NONE");
        assertEquals(0,result.storedRecordCount());
        assertEquals(1,value(result,"policyExcludedRecordCount"));
        assertEquals(1,result.checkpointCommittedCount()); assertEquals(0,result.unconfirmedDocs());
        assertEquals("POLICY_EXCLUDED",result.status());
    }
    @Test void unknownQuarantineCommitConfirmsNeitherNormalNorExcludedOriginal() throws Exception {
        var service=quarantineService();
        @SuppressWarnings("unchecked") var store=(EmbeddingStore<dev.langchain4j.data.segment.TextSegment>)ReflectionTestUtils.getField(service,"embeddingStore");
        doAnswer(a -> { throw new java.net.SocketTimeoutException("synthetic"); }).when(store).addAll(anyList(),anyList(),anyList());
        var result=ingest(service,dataset("unknown-q","fixture-q"),temp.resolve("unknown-q-state.json"),"METADATA_ONLY");
        assertEquals(0,result.storedRecordCount()); assertEquals(0,value(result,"policyExcludedRecordCount"));
        assertEquals(0,result.checkpointCommittedCount()); assertEquals("UNKNOWN_COMMIT",result.status());
    }
    @Test void laterMissingReceiptDoesNotEraseAnAlreadyDurableSibling() throws Exception {
        var store=mock(VectorStoreService.class); var confirmed=mock(VectorStoreService.VectorRecordReceipt.class);
        when(confirmed.durable()).thenReturn(true); when(confirmed.reasonCode()).thenReturn("complete");
        when(store.enqueueWithReceipt(anyString(),anyString(),anyString(),anyMap())).thenReturn(confirmed,null);
        var result=ingest(store,dataset("missing-later","fixture-a","fixture-b"),temp.resolve("missing-later-state.json"),"METADATA_ONLY");
        assertEquals(1,result.storedRecordCount()); assertEquals(0,result.checkpointCommittedCount());
        assertEquals(2,result.unconfirmedDocs()); assertFalse(result.complete());
        assertEquals("vector_receipt_missing",result.reasonCode());
    }
    @Test void durableQuarantineAndUnknownNormalSiblingCannotCommitTheBatch() throws Exception {
        var service=quarantineService(); ReflectionTestUtils.setField(service,"batchSize",1);
        @SuppressWarnings("unchecked") var store=(EmbeddingStore<dev.langchain4j.data.segment.TextSegment>)ReflectionTestUtils.getField(service,"embeddingStore");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(a -> { if(calls.incrementAndGet()==2) throw new java.net.SocketTimeoutException("synthetic"); return null; })
                .when(store).addAll(anyList(),anyList(),anyList());
        Path state=temp.resolve("partial-mixed-state.json");
        var result=ingest(service,dataset("partial-mixed","fixture-q","fixture-n"),state,"METADATA_ONLY");
        assertEquals(0,result.storedRecordCount()); assertEquals(1,value(result,"policyExcludedRecordCount"));
        assertEquals(0,result.checkpointCommittedCount()); assertEquals(2,result.unconfirmedDocs());
        assertEquals("UNKNOWN_COMMIT",result.status()); assertFalse(Files.exists(state));
    }
    private VectorStoreService quarantineService() {
        var service=service(); var guard=mock(com.example.lms.service.guard.VectorPoisonGuard.class);
        when(guard.inspectIngest(anyString(),anyString(),anyMap(),anyString())).thenAnswer(a ->
                new com.example.lms.service.guard.VectorPoisonGuard.IngestDecision(!((String)a.getArgument(0)).endsWith("-q"),a.getArgument(1),a.getArgument(2),"synthetic",1));
        ReflectionTestUtils.setField(service,"vectorPoisonGuard",guard); return service;
    }
    private Path dataset(String name,String... sids) throws Exception {
        Path file=temp.resolve(name+".jsonl"); var lines=new StringBuilder();
        for(String sid:sids) lines.append("{\"question\":\"fixture ").append(sid).append("\",\"answer\":\"fixture\",\"sessionId\":\"").append(sid).append("\"}\n");
        Files.writeString(file,lines.toString()); return file;
    }
    private TrainRagIngestService.IngestOutcome ingest(VectorStoreService store,Path jsonl,Path state,String projection) {
        var props=new UawAutolearnProperties(); props.getRetrain().setIngestStatePath(state.toString());
        props.getRetrain().setVectorProjectionMode(projection);
        return new TrainRagIngestService(store,mock(VectorSidService.class),props).ingestNewSamplesDetailed(jsonl,"fixture",()->false);
    }
    private static void chunks(VectorStoreService service,String first,String second) {
        var chunks=mock(com.example.lms.service.vector.DocumentChunkingService.class);
        when(chunks.split(anyString(),anyMap())).thenReturn(List.of(
                new com.example.lms.service.vector.DocumentChunkingService.Chunk(first,Map.of(VectorMetaKeys.META_CHUNK_ID,"original#0")),
                new com.example.lms.service.vector.DocumentChunkingService.Chunk(second,Map.of(VectorMetaKeys.META_CHUNK_ID,"original#1"))));
        ReflectionTestUtils.setField(service,"documentChunkingService",chunks);
    }
    static Object enqueue(VectorStoreService service,String id,String sid,String text,Map<String,Object> meta) throws Exception {
        var method=Arrays.stream(VectorStoreService.class.getMethods()).filter(m->m.getName().equals("enqueueWithReceipt")).findFirst();
        assertTrue(method.isPresent(),"original-record receipt API is required; aggregate flush is insufficient");
        return method.orElseThrow().invoke(service,id,sid,text,meta);
    }
    static Object value(Object receipt,String name) throws Exception {return receipt.getClass().getMethod(name).invoke(receipt);}
    @SuppressWarnings("unchecked") static VectorStoreService service() {
        var model=mock(EmbeddingModel.class); var store=(EmbeddingStore<dev.langchain4j.data.segment.TextSegment>)mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(a->Response.from(((List<?>)a.getArgument(0)).stream().map(x->Embedding.from(new float[]{1})).toList()));
        var s=new VectorStoreService(model,store); ReflectionTestUtils.setField(s,"batchSize",1000);
        ReflectionTestUtils.setField(s,"initialBackoffMs",1000L); ReflectionTestUtils.setField(s,"maxBackoffMs",60000L); return s;
    }
}
