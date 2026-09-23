package com.example.lms.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedEmbeddingStoreWriteContractTest {

    @Test
    void addReturnsTheIdentifierActuallyStoredUpstream() {
        InMemoryEmbeddingStore<TextSegment> upstream = new InMemoryEmbeddingStore<>();
        FederatedEmbeddingStore store = federatedStore(upstream);
        Embedding embedding = Embedding.from(new float[] { 1.0f, 0.0f });

        try {
            String returnedId = store.add(embedding);

            EmbeddingSearchResult<TextSegment> result = search(upstream, embedding);
            assertEquals(1, result.matches().size());
            assertEquals(returnedId, result.matches().get(0).embeddingId());
        } finally {
            store.shutdownPool();
        }
    }

    @Test
    void explicitIdentifierAddAcceptsTheAbsentSegment() {
        InMemoryEmbeddingStore<TextSegment> upstream = new InMemoryEmbeddingStore<>();
        FederatedEmbeddingStore store = federatedStore(upstream);
        Embedding embedding = Embedding.from(new float[] { 1.0f, 0.0f });
        String expectedId = "stable-vector-id";

        try {
            assertDoesNotThrow(() -> store.add(expectedId, embedding));

            EmbeddingSearchResult<TextSegment> result = search(upstream, embedding);
            assertEquals(1, result.matches().size());
            assertEquals(expectedId, result.matches().get(0).embeddingId());
            assertNull(result.matches().get(0).embedded());
        } finally {
            store.shutdownPool();
        }
    }

    @Test
    void writeDeadlineReportsPerStorePartialOutcome() throws Exception {
        InMemoryEmbeddingStore<TextSegment> fast = new InMemoryEmbeddingStore<>();
        NonInterruptibleWriteStore stuck = new NonInterruptibleWriteStore();
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("fast", fast),
                        new FederatedEmbeddingStore.NamedStore("stuck", stuck)),
                new TopicRoutingSettings(
                        Map.of("default", Map.of("fast", 1.0d, "stuck", 1.0d)),
                        1),
                100,
                2);

        try {
            FederatedEmbeddingStore.FederatedWriteResult result = store.writeAllWithinDeadline(
                    List.of("stable-id"),
                    List.of(Embedding.from(new float[] { 1.0f, 0.0f })),
                    List.of(TextSegment.from("safe text")),
                    100);

            assertTrue(stuck.entered.await(1, TimeUnit.SECONDS));
            assertEquals(FederatedEmbeddingStore.FederatedStoreWriteStatus.SUCCEEDED,
                    result.outcomes().get("fast"));
            assertEquals(FederatedEmbeddingStore.FederatedStoreWriteStatus.DEADLINE_EXCEEDED,
                    result.outcomes().get("stuck"));
            assertEquals(1, result.succeededCount());
            assertEquals(1L, stuck.finished.getCount(),
                    "deadline is not proof that the interrupt-ignoring worker stopped");
        } finally {
            stuck.release.countDown();
            assertTrue(stuck.finished.await(1, TimeUnit.SECONDS));
            store.shutdownPool();
        }
    }

    @Test
    void publicAddAllStillFailsWhenEveryStoreRejectsTheWrite() {
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("first", new FailingWriteStore()),
                        new FederatedEmbeddingStore.NamedStore("second", new FailingWriteStore())),
                new TopicRoutingSettings(
                        Map.of("default", Map.of("first", 1.0d, "second", 1.0d)),
                        1),
                100,
                2);

        try {
            assertThrows(IllegalStateException.class, () -> store.addAll(
                    List.of("stable-id"),
                    List.of(Embedding.from(new float[] { 1.0f, 0.0f })),
                    List.of(TextSegment.from("safe text"))));
        } finally {
            store.shutdownPool();
        }
    }

    @Test
    void escapedWorkerErrorStillProducesOneRedactedFailedOutcome() {
        String rawStoreId = "error-store token=private-write-store";
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore(rawStoreId, new ErrorWriteStore())),
                new TopicRoutingSettings(Map.of("default", Map.of(rawStoreId, 1.0d)), 1),
                100,
                1);

        try {
            FederatedEmbeddingStore.FederatedWriteResult result = store.writeAllWithinDeadline(
                    List.of("stable-id"),
                    List.of(Embedding.from(new float[] { 1.0f, 0.0f })),
                    List.of(TextSegment.from("safe text")),
                    100);

            assertEquals(1, result.outcomes().size());
            assertEquals(List.of(FederatedEmbeddingStore.FederatedStoreWriteStatus.FAILED),
                    result.outcomes().values().stream().toList());
            assertEquals(0, result.succeededCount());
            assertFalse(result.outcomes().toString().contains(rawStoreId));
            assertFalse(result.outcomes().toString().contains("private-write-store"));
        } finally {
            store.shutdownPool();
        }
    }

    @Test
    void timedOutWorkerObservesTheAdmissionSnapshotNotLaterCallerMutation() throws Exception {
        CapturingNonInterruptibleWriteStore stuck = new CapturingNonInterruptibleWriteStore();
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("stuck", stuck)),
                new TopicRoutingSettings(Map.of("default", Map.of("stuck", 1.0d)), 1),
                100,
                1);
        List<String> ids = new ArrayList<>(List.of("original-id"));
        List<Embedding> embeddings = new ArrayList<>(
                List.of(Embedding.from(new float[] { 1.0f, 0.0f })));
        List<TextSegment> segments = new ArrayList<>(List.of(TextSegment.from("original-segment")));

        try {
            FederatedEmbeddingStore.FederatedWriteResult result = store.writeAllWithinDeadline(
                    ids, embeddings, segments, 100);
            assertEquals(FederatedEmbeddingStore.FederatedStoreWriteStatus.DEADLINE_EXCEEDED,
                    result.outcomes().get("stuck"));
            assertTrue(stuck.entered.await(1, TimeUnit.SECONDS));

            ids.set(0, "mutated-id");
            embeddings.set(0, Embedding.from(new float[] { 0.0f, 1.0f }));
            segments.set(0, TextSegment.from("mutated-segment"));
            stuck.release.countDown();
            assertTrue(stuck.finished.await(1, TimeUnit.SECONDS));

            assertEquals("original-id", stuck.observedId.get());
            assertEquals("original-segment", stuck.observedSegment.get());
        } finally {
            stuck.release.countDown();
            stuck.finished.await(1, TimeUnit.SECONDS);
            store.shutdownPool();
        }
    }

    private static FederatedEmbeddingStore federatedStore(InMemoryEmbeddingStore<TextSegment> upstream) {
        String storeId = "memory";
        return new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore(storeId, upstream)),
                new TopicRoutingSettings(Map.of("default", Map.of(storeId, 1.0d)), 1),
                100,
                1);
    }

    private static EmbeddingSearchResult<TextSegment> search(
            InMemoryEmbeddingStore<TextSegment> upstream,
            Embedding query) {
        return upstream.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(1)
                .minScore(0.0)
                .build());
    }

    private static final class NonInterruptibleWriteStore extends InMemoryEmbeddingStore<TextSegment> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            entered.countDown();
            try {
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(1, TimeUnit.DAYS);
                    } catch (InterruptedException ignored) {
                        // Deliberately model an upstream store that ignores interruption.
                    }
                }
                super.addAll(ids, embeddings, embedded);
            } finally {
                finished.countDown();
            }
        }
    }

    private static final class FailingWriteStore extends InMemoryEmbeddingStore<TextSegment> {
        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            throw new IllegalStateException("fixed-fixture-failure");
        }
    }

    private static final class ErrorWriteStore extends InMemoryEmbeddingStore<TextSegment> {
        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            throw new AssertionError("fixed-fixture-error");
        }
    }

    private static final class CapturingNonInterruptibleWriteStore extends InMemoryEmbeddingStore<TextSegment> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<String> observedId = new AtomicReference<>();
        private final AtomicReference<String> observedSegment = new AtomicReference<>();

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            entered.countDown();
            try {
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(1, TimeUnit.DAYS);
                    } catch (InterruptedException ignored) {
                        // Deliberately model an upstream store that ignores interruption.
                    }
                }
                observedId.set(ids.get(0));
                observedSegment.set(embedded.get(0).text());
            } finally {
                finished.countDown();
            }
        }
    }
}
