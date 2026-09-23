package com.example.lms.vector;

import com.example.lms.service.VectorStoreService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FederatedEmbeddingStoreIdCapabilityTest {
    private static final Embedding VECTOR=Embedding.from(new float[]{1.0f,0.0f});
    private static final TextSegment SEGMENT=TextSegment.from("capability fixture");

    @AfterEach void clearContext() { MDC.clear(); com.example.lms.search.TraceStore.clear(); }

    @ParameterizedTest
    @ValueSource(strings={"single","segment","bulk","bulkSegments","explicit","explicitBulk"})
    void idRejectingUpstreamCannotSilentlyWriteUnderAnonymousIds(String operation) {
        IdCapabilityStore upstream=new IdCapabilityStore(false);
        FederatedEmbeddingStore store=federated(upstream);
        try {
            assertThrows(IllegalStateException.class,()->write(store,operation));
            assertEquals(1,upstream.idCalls.get());
            assertEquals(0,upstream.anonymousCalls.get());
            assertTrue(storedIds(upstream).isEmpty());
        } finally { store.shutdownPool(); }
    }

    @ParameterizedTest
    @ValueSource(strings={"single","segment","bulk","bulkSegments","explicit","explicitBulk"})
    void idAwareUpstreamStoresEveryReturnedOrSuppliedIdentifier(String operation) {
        InMemoryEmbeddingStore<TextSegment> upstream=new InMemoryEmbeddingStore<>();
        FederatedEmbeddingStore store=federated(upstream);
        try {
            List<String> ids=write(store,operation);
            assertEquals(new HashSet<>(ids),new HashSet<>(storedIds(upstream)));
            assertEquals(ids.size(),storedIds(upstream).size());
        } finally { store.shutdownPool(); }
    }

    @Test
    void fixtureReallySupportsAnonymousWritesAndRejectsExplicitBulkIds() {
        IdCapabilityStore upstream=new IdCapabilityStore(false);
        assertThrows(UnsupportedFeatureException.class,()->upstream.addAll(List.of("requested"),List.of(VECTOR),List.of(SEGMENT)));
        List<String> anonymous=upstream.addAll(List.of(VECTOR),List.of(SEGMENT));
        assertEquals(anonymous,storedIds(upstream));
        assertFalse(anonymous.contains("requested"));
    }

    @Test
    void unsupportedStoreIsFailedWhileOneIdAwareStoreMakesTheBatchSuccessful() {
        InMemoryEmbeddingStore<TextSegment> good=new InMemoryEmbeddingStore<>();
        IdCapabilityStore bad=new IdCapabilityStore(false);
        FederatedEmbeddingStore store=federated(good,bad);
        try {
            var result=store.writeAllWithinDeadline(List.of("stable"),List.of(VECTOR),List.of(SEGMENT),1_000);
            assertEquals(FederatedEmbeddingStore.FederatedStoreWriteStatus.SUCCEEDED,result.outcomes().get("store0"));
            assertEquals(FederatedEmbeddingStore.FederatedStoreWriteStatus.FAILED,result.outcomes().get("store1"));
            assertEquals(1,result.succeededCount());
            assertEquals(List.of("stable"),storedIds(good));
            assertEquals(0,bad.anonymousCalls.get());
        } finally { store.shutdownPool(); }
    }

    @Test
    void publicFanoutRetainsPartialSuccessWithoutAnonymousSideWrites() {
        InMemoryEmbeddingStore<TextSegment> good=new InMemoryEmbeddingStore<>();
        IdCapabilityStore bad=new IdCapabilityStore(false);
        FederatedEmbeddingStore store=federated(good,bad);
        try {
            String id=assertDoesNotThrow(()->store.add(VECTOR,SEGMENT));
            assertEquals(List.of(id),storedIds(good));
            assertTrue(storedIds(bad).isEmpty());
            assertEquals(0,bad.anonymousCalls.get());
        } finally { store.shutdownPool(); }
    }

    @Test
    void ordinaryUnsupportedOperationKeepsItsExistingFailedOutcome() {
        IdCapabilityStore upstream=new IdCapabilityStore(true);
        FederatedEmbeddingStore store=federated(upstream);
        try {
            var result=store.writeAllWithinDeadline(List.of("stable"),List.of(VECTOR),List.of(SEGMENT),1_000);
            assertEquals(FederatedEmbeddingStore.FederatedStoreWriteStatus.FAILED,result.outcomes().get("store0"));
            assertEquals(0,result.succeededCount());
            assertEquals(0,upstream.anonymousCalls.get());
        } finally { store.shutdownPool(); }
    }

    @Test
    void actualFlushKeepsStableIdPendingUntilAnIdPreservingRetrySucceeds() throws Exception {
        IdCapabilityStore upstream=new IdCapabilityStore(false);
        FederatedEmbeddingStore store=federated(upstream);
        EmbeddingModel model=new EmbeddingModel() {
            @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream().map(s->VECTOR).toList());
            }
        };
        VectorStoreService service=new VectorStoreService(model,store);
        set(service,"batchSize",1_000); set(service,"queueMaxPending",2_048);
        set(service,"shadowWriteEnabled",false); set(service,"initialBackoffMs",1_000L);
        set(service,"maxBackoffMs",60_000L);
        try {
            service.enqueue("stable-flush-id","fixture-session","fixture payload",Map.of());
            var first=service.flush();
            assertFalse(first.durable());
            assertEquals("store_failure",first.reasonCode());
            assertEquals(0,first.succeededCount());
            assertEquals(1,first.pendingCount());
            assertEquals(1,service.pendingSize());
            assertTrue(storedIds(upstream).isEmpty());
            upstream.supportsIds=true;
            set(service,"backoffUntilEpochMs",0L);
            var retry=service.flush();
            assertTrue(retry.durable());
            assertEquals(1,retry.succeededCount());
            assertEquals(0,service.pendingSize());
            assertEquals(List.of("stable-flush-id"),storedIds(upstream));
            assertEquals(0,upstream.anonymousCalls.get());
        } finally { store.shutdownPool(); }
    }

    private static List<String> write(FederatedEmbeddingStore store,String operation) {
        return switch(operation) {
            case "single" -> List.of(store.add(VECTOR));
            case "segment" -> List.of(store.add(VECTOR,SEGMENT));
            case "bulk" -> store.addAll(List.of(VECTOR,VECTOR));
            case "bulkSegments" -> store.addAll(List.of(VECTOR,VECTOR),List.of(SEGMENT,SEGMENT));
            case "explicit" -> { store.add("stable-explicit",VECTOR); yield List.of("stable-explicit"); }
            case "explicitBulk" -> { var ids=List.of("stable-a","stable-b"); store.addAll(ids,List.of(VECTOR,VECTOR),List.of(SEGMENT,SEGMENT)); yield ids; }
            default -> throw new IllegalArgumentException("fixture operation");
        };
    }

    @SafeVarargs
    private static FederatedEmbeddingStore federated(EmbeddingStore<TextSegment>... stores) {
        List<FederatedEmbeddingStore.NamedStore> named=new ArrayList<>();
        for(int i=0;i<stores.length;i++) named.add(new FederatedEmbeddingStore.NamedStore("store"+i,stores[i]));
        return new FederatedEmbeddingStore(named,new TopicRoutingSettings(Map.of(),1),1_000,stores.length);
    }
    private static List<String> storedIds(InMemoryEmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(VECTOR).maxResults(10).minScore(0.0).build())
                .matches().stream().map(m->m.embeddingId()).toList();
    }
    private static void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    private static final class IdCapabilityStore extends InMemoryEmbeddingStore<TextSegment> {
        final AtomicInteger idCalls=new AtomicInteger();
        final AtomicInteger anonymousCalls=new AtomicInteger();
        final boolean ordinaryUnsupported;
        volatile boolean supportsIds;
        IdCapabilityStore(boolean ordinaryUnsupported) { this.ordinaryUnsupported=ordinaryUnsupported; }
        @Override public void addAll(List<String> ids,List<Embedding> embeddings,List<TextSegment> segments) {
            idCalls.incrementAndGet();
            if(supportsIds) { super.addAll(ids,embeddings,segments); return; }
            if(ordinaryUnsupported) throw new UnsupportedOperationException("fixture capability");
            throw new UnsupportedFeatureException("fixture capability");
        }
        @Override public List<String> addAll(List<Embedding> embeddings,List<TextSegment> segments) {
            int call=anonymousCalls.incrementAndGet();
            List<String> ids=new ArrayList<>();
            for(int i=0;i<embeddings.size();i++) ids.add("anonymous-"+call+"-"+i);
            super.addAll(ids,embeddings,segments);
            return ids;
        }
        @Override public List<String> addAll(List<Embedding> embeddings) {
            return addAll(embeddings,Collections.nCopies(embeddings.size(),null));
        }
    }
}
