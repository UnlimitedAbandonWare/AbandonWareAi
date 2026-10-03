package com.example.lms.service.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingBatchContractTest {

    @Test
    void overlappingBatchesShareTheActualDelegateComputation() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger batchCalls = new AtomicInteger();
        EmbeddingModel delegate = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                batchCalls.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1,2,3})).toList());
            }
        };
        var model = new DecoratingEmbeddingModel(delegate, new EmbeddingCache.InMemory(), Duration.ofMinutes(1));
        var input = List.of(TextSegment.from("shared"));
        var owner = new java.util.concurrent.FutureTask<>(() -> model.embedAll(input).content());
        var follower = new java.util.concurrent.FutureTask<>(() -> model.embedAll(input).content());
        Thread first = new Thread(owner, "f04-owner"), second = new Thread(follower, "f04-follower");
        try {
            first.start(); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            second.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (second.getState() != Thread.State.WAITING && second.getState() != Thread.State.TIMED_WAITING
                    && second.isAlive() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(second.isAlive(), "follower reaches the shared wait while owner is held");
            assertEquals(1, batchCalls.get(), "overlap must join the real batch computation, not an empty probe");
            release.countDown();
            assertArrayEquals(owner.get(2, java.util.concurrent.TimeUnit.SECONDS).get(0).vector(),
                    follower.get(2, java.util.concurrent.TimeUnit.SECONDS).get(0).vector());
        } finally {
            release.countDown(); first.join(3000); second.join(3000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive());
        }
    }
    @Test
    void sameBatchComputesTwentyKeysForOneHundredPositions() {
        AtomicInteger computed = new AtomicInteger();
        EmbeddingModel delegate = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                computed.addAndGet(segments.size());
                return Response.from(segments.stream().map(s ->
                        Embedding.from(new float[]{Integer.parseInt(s.text().substring(4)), 1, 2})).toList());
            }
        };
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(delegate,
                new EmbeddingCache.InMemory(), Duration.ofMinutes(1));
        List<TextSegment> segments = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> TextSegment.from("key-" + (i % 20))).toList();
        List<Embedding> result = model.embedAll(segments).content();
        assertEquals(20, computed.get(), "delegate input is unique canonical keys, not logical positions");
        assertEquals(100, result.size());
        for (int i = 0; i < 100; i++) assertEquals(i % 20, result.get(i).vector()[0]);
        result.get(0).vector()[0] = 999;
        assertEquals(0, result.get(20).vector()[0], "duplicated output positions must not alias mutable vectors");
    }
}
