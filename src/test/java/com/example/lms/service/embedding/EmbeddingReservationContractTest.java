package com.example.lms.service.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import com.example.lms.search.TraceStore;
import com.example.lms.vector.EmbeddingFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingReservationContractTest {
    private static final Duration TTL = Duration.ofMinutes(1);
    @AfterEach void clear() { TraceStore.clear(); com.abandonware.ai.addons.budget.TimeBudgetContext.clear(); }

    @Test
    void providerAndScalarCallerCannotMutateReservedCacheOrJoiners() {
        var cache = new EmbeddingCache.InMemory();
        var owner = cache.reserveBatch(List.of("key"), 1000).get("key");
        var join = cache.reserveBatch(List.of("key"), 1000).get("key");
        float[] providerVector = {1, 2};
        owner.complete(providerVector, TTL, true);
        providerVector[0] = 99;
        assertArrayEquals(new float[]{1,2}, join.await(1000), "provider array is not cache storage");
        float[] scalarHit = cache.getOrCompute("key", () -> fail("hit"), TTL);
        scalarHit[0] = 88;
        assertArrayEquals(new float[]{1,2}, join.await(1000), "scalar callers cannot corrupt a shared flight");
        float[] batchHit = cache.reserveBatch(List.of("key"), 1000).get("key").await(1000);
        batchHit[0] = 77;
        assertArrayEquals(new float[]{1,2}, cache.getOrCompute("key", () -> fail("hit"), TTL));
    }

    @Test
    void followerDeadlineDoesNotCancelOwnerOrLongWaiter() throws Exception {
        var cache = new EmbeddingCache.InMemory();
        var owner = cache.reserveBatch(List.of("key"), 1000).get("key");
        var shortWait = cache.reserveBatch(List.of("key"), 1000).get("key");
        var longWait = cache.reserveBatch(List.of("key"), 1000).get("key");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> longWait.await(2000));
            long begin = System.nanoTime();
            assertEquals(0, shortWait.await(20).length);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin) < 500);
            shortWait.close();
            assertFalse(result.isDone());
            owner.complete(new float[]{3}, TTL, true);
            assertArrayEquals(new float[]{3}, result.get(1, TimeUnit.SECONDS));
        } finally { owner.close(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }

    @Test
    void invalidatedOldReservationCannotReplaceNewOwnerOrEntry() {
        var cache = new EmbeddingCache.InMemory();
        var old = cache.reserveBatch(List.of("key"), 1000).get("key");
        cache.invalidate("key");
        var replacement = cache.reserveBatch(List.of("key"), 1000).get("key");
        old.complete(new float[]{1}, TTL, true);
        assertEquals(EmbeddingCache.ReservationState.JOIN, cache.reserveBatch(List.of("key"),1000).get("key").state());
        replacement.complete(new float[]{2}, TTL, true);
        old.close();
        assertArrayEquals(new float[]{2}, cache.getOrCompute("key", () -> fail("hit"), TTL));
    }

    @Test
    void crossOwnedKeysCanCompleteBeforeTheirJoins() throws Exception {
        var cache = new EmbeddingCache.InMemory();
        var x = cache.reserveBatch(List.of("x"),1000).get("x");
        var y = cache.reserveBatch(List.of("y"),1000).get("y");
        var joinX = cache.reserveBatch(List.of("x"),1000).get("x");
        var joinY = cache.reserveBatch(List.of("y"),1000).get("y");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { x.complete(new float[]{1},TTL,true); return joinY.await(1000); });
            var b = executor.submit(() -> { y.complete(new float[]{2},TTL,true); return joinX.await(1000); });
            assertArrayEquals(new float[]{2},a.get(2,TimeUnit.SECONDS));
            assertArrayEquals(new float[]{1},b.get(2,TimeUnit.SECONDS));
        } finally { x.close(); y.close(); executor.shutdownNow(); executor.awaitTermination(2,TimeUnit.SECONDS); }
    }

    @Test
    void boundedFallbackDoesNotSelfJoinAndReleasesEveryReservation() {
        var cache = new EmbeddingCache.InMemory();
        AtomicInteger singles = new AtomicInteger();
        EmbeddingModel broken = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) { throw new IllegalStateException("synthetic batch failure"); }
            public Response<Embedding> embed(TextSegment segment) { singles.incrementAndGet(); throw new IllegalStateException("synthetic single failure"); }
        };
        var input = java.util.stream.IntStream.range(0,20).mapToObj(i->TextSegment.from("item-"+i)).toList();
        long begin = System.nanoTime();
        var result = new DecoratingEmbeddingModel(broken,cache,TTL).embedAll(input).content();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<1000, "no own-flight 30-second wait");
        assertEquals(3,singles.get());
        assertEquals(20,result.size());
        assertTrue(result.stream().allMatch(e->e.vector().length==0));
        assertEquals(0, ((Map<?,?>)ReflectionTestUtils.getField(cache,"inflight")).size());
    }

    @Test
    void invalidVectorsAreNotPublishedAndDoNotStrandOtherKeys() {
        var cache = new EmbeddingCache.InMemory();
        AtomicInteger computed = new AtomicInteger();
        EmbeddingModel delegate = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                computed.addAndGet(segments.size());
                return Response.from(segments.stream().map(s->Embedding.from(s.text().equals("good")
                        ? new float[]{1,2,3} : s.text().equals("dimension") ? new float[]{1,2}
                        : new float[]{Float.NaN,2,3})).toList());
            }
        };
        var model = new DecoratingEmbeddingModel(delegate,cache,TTL,fingerprint("model-one",3));
        var input=List.of(TextSegment.from("good"),TextSegment.from("dimension"),TextSegment.from("nan"));
        var output=model.embedAll(input).content();
        assertEquals(3,output.get(0).vector().length);
        assertEquals(0,output.get(1).vector().length);
        assertEquals(0,output.get(2).vector().length);
        model.embedAll(input);
        assertEquals(5,computed.get(), "only the valid same-fingerprint key is retained");
        assertEquals(0,((Map<?,?>)ReflectionTestUtils.getField(cache,"inflight")).size());
    }

    @Test
    void metadataAndModelIdentityAreNotDeduplicatedByTextAlone() {
        AtomicInteger computed = new AtomicInteger();
        EmbeddingModel delegate = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                computed.addAndGet(segments.size());
                return Response.from(segments.stream().map(s->Embedding.from(new float[]{1,2,3})).toList());
            }
        };
        var cache=new EmbeddingCache.InMemory();
        var one=new DecoratingEmbeddingModel(delegate,cache,TTL,fingerprint("one",3));
        var two=new DecoratingEmbeddingModel(delegate,cache,TTL,fingerprint("two",3));
        var input=List.of(TextSegment.from("same",Metadata.from(Map.of("domain","one","doc_id","a"))),
                TextSegment.from("same",Metadata.from(Map.of("domain","one","doc_id","b"))),
                TextSegment.from("same",Metadata.from(Map.of("domain","two","doc_id","a"))));
        one.embedAll(input); one.embedAll(input); two.embedAll(input);
        assertEquals(6,computed.get());
    }

    @Test
    void rejectedCapacityOrExpiredCallerDoesNotInvokeDelegate() {
        var cache = new EmbeddingCache.InMemory(Clock.systemUTC(),1);
        var held=cache.reserveBatch(List.of("held"),1000).get("held");
        AtomicInteger calls=new AtomicInteger();
        EmbeddingModel delegate=new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                calls.incrementAndGet(); return Response.from(List.of(Embedding.from(new float[]{1})));
            }
        };
        var model=new DecoratingEmbeddingModel(delegate,cache,TTL);
        try { assertEquals(0,model.embedAll(List.of(TextSegment.from("other"))).content().get(0).vector().length); }
        finally { held.close(); }
        var budget=new com.abandonware.ai.addons.budget.TimeBudget(5000);budget.cancel();
        com.abandonware.ai.addons.budget.TimeBudgetContext.set(budget);
        model.embedAll(List.of(TextSegment.from("other")));
        assertEquals(0,calls.get());
        assertEquals(0,((Map<?,?>)ReflectionTestUtils.getField(cache,"inflight")).size());
    }

    private static EmbeddingFingerprint fingerprint(String model,int dimension) {
        var fp=new EmbeddingFingerprint();
        ReflectionTestUtils.setField(fp,"provider","synthetic");
        ReflectionTestUtils.setField(fp,"model",model);
        ReflectionTestUtils.setField(fp,"dimensions",dimension);
        return fp;
    }
}
