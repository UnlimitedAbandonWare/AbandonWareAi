package com.example.lms.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedEmbeddingStoreTest {

    @Test
    void federatedEmbeddingStoreDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/vector/FederatedEmbeddingStore.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "federated embedding store fail-soft paths need safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void failSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/vector/FederatedEmbeddingStore.java"),
                StandardCharsets.UTF_8);

        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("log.warn(\"Federated search fail-soft on store {}: {}\", ns.id()"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e2), 180)"));
        assertTrue(source.contains("log.warn(\"Federated search fail-soft on store {}. errorHash={} errorLength={}\","));
        assertTrue(source.contains("safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("Federated addAll failed on store {}. errorHash={} errorLength={}"));
        assertTrue(source.contains("Federated search fail-soft on store {}. errorHash={} errorLength={}"));
        assertTrue(source.contains("Federated addAll unsupported ids path store={}"));
        assertTrue(source.contains("[AWX2AF2][vector][store-error] store={} requestedK={} errorType={}"));
        assertTrue(source.contains("[AWX2AF2][vector][timeout] timeout breadcrumb suppressed store={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void searchReturnsHealthyStoreResultsWhenAnotherStoreTimesOut() {
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("fast", new StaticStore("fast", 0)),
                        new FederatedEmbeddingStore.NamedStore("slow", new StaticStore("slow", 500))),
                new TopicRoutingSettings(Map.of("default", Map.of("fast", 1.0d, "slow", 1.0d)), 1),
                75,
                2);

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                .maxResults(2)
                .minScore(0.0)
                .build());

        assertEquals(1, result.matches().size());
        assertEquals("fast doc", result.matches().get(0).embedded().text());
    }

    @Test
    void unmatchedRoutingWeightsFallBackToStoreIdsInsteadOfDroppingAllK() {
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("composite", new StaticStore("composite", 0))),
                new TopicRoutingSettings(Map.of("default", Map.of("pinecone", 1.0d)), 1),
                100,
                1);

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                .maxResults(1)
                .minScore(0.0)
                .build());

        assertEquals(1, result.matches().size());
        assertEquals("composite doc", result.matches().get(0).embedded().text());
    }

    @Test
    void timedOutStoreIsCancelledWithoutInterruptingWorker() throws Exception {
        TraceStore.clear();
        StaticStore slow = new StaticStore("slow", 300);
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("fast", new StaticStore("fast", 0)),
                        new FederatedEmbeddingStore.NamedStore("slow", slow)),
                new TopicRoutingSettings(Map.of("default", Map.of("fast", 1.0d, "slow", 1.0d)), 1),
                50,
                2);

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(2)
                    .minScore(0.0)
                    .build());

            assertEquals(1, result.matches().size());
            assertEquals("fast doc", result.matches().get(0).embedded().text());
            assertTrue(slow.started.await(1, TimeUnit.SECONDS));
            assertTrue(slow.finished.await(2, TimeUnit.SECONDS));
            assertFalse(slow.interrupted.get());
            assertEquals("no_interrupt", TraceStore.get("vector.federated.cancelMode"));
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void searchUsesOneTotalDeadlineAcrossAllStores() throws Exception {
        TraceStore.clear();
        NonInterruptibleLatchStore first = new NonInterruptibleLatchStore("first");
        NonInterruptibleLatchStore second = new NonInterruptibleLatchStore("second");
        NonInterruptibleLatchStore third = new NonInterruptibleLatchStore("third");
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("first", first),
                        new FederatedEmbeddingStore.NamedStore("second", second),
                        new FederatedEmbeddingStore.NamedStore("third", third)),
                new TopicRoutingSettings(
                        Map.of("default", Map.of("first", 1.0d, "second", 1.0d, "third", 1.0d)),
                        1),
                100,
                3);

        try {
            long startedNanos = System.nanoTime();
            EmbeddingSearchResult<TextSegment> result = store.search(searchRequest(3, null));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertTrue(first.entered.await(1, TimeUnit.SECONDS));
            assertTrue(second.entered.await(1, TimeUnit.SECONDS));
            assertTrue(third.entered.await(1, TimeUnit.SECONDS));
            assertTrue(elapsedMs < 225L,
                    () -> "search consumed a timeout per store: " + elapsedMs + "ms");
            assertTrue(result.matches().isEmpty());
        } finally {
            first.releaseAndAwaitCurrentWorkers();
            second.releaseAndAwaitCurrentWorkers();
            third.releaseAndAwaitCurrentWorkers();
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void unfinishedStoreRetainsItsAdmissionWithoutStarvingAnotherStore() throws Exception {
        TraceStore.clear();
        NonInterruptibleLatchStore stuck = new NonInterruptibleLatchStore("stuck");
        StaticStore fast = new StaticStore("fast", 0);
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore("stuck", stuck),
                        new FederatedEmbeddingStore.NamedStore("fast", fast)),
                new TopicRoutingSettings(Map.of(
                        "stuck", Map.of("stuck", 1.0d, "fast", 0.0d),
                        "fast", Map.of("stuck", 0.0d, "fast", 1.0d)), 0),
                100,
                2);

        try {
            EmbeddingSearchResult<TextSegment> first = store.search(searchRequest(1, topicFilter("stuck")));
            Object firstUnfinished = TraceStore.get("vector.federated.search.unfinishedCount");
            EmbeddingSearchResult<TextSegment> repeated = store.search(searchRequest(1, topicFilter("stuck")));
            Object repeatedUnfinished = TraceStore.get("vector.federated.search.unfinishedCount");
            EmbeddingSearchResult<TextSegment> healthy = store.search(searchRequest(1, topicFilter("fast")));

            assertTrue(first.matches().isEmpty());
            assertTrue(repeated.matches().isEmpty());
            assertEquals(1, firstUnfinished);
            assertEquals(1, repeatedUnfinished);
            assertEquals(1, stuck.invocations.get(),
                    "a still-running store must retain its one worker-lifetime admission");
            assertEquals(1, healthy.matches().size(),
                    "one stuck store must not consume every future search slot");
            assertEquals("fast doc", healthy.matches().get(0).embedded().text());
        } finally {
            stuck.releaseAndAwaitCurrentWorkers();
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void interruptedSearchRestoresCallerInterruptAndStopsWaitingOnLaterStores() throws Exception {
        TraceStore.clear();
        String fastId = "fast token=private-fast-store";
        String currentId = "current token=private-current-store";
        String laterId = "later token=private-later-store";
        StaticStore fast = new StaticStore(fastId, 0);
        LatchStore current = new LatchStore(currentId);
        LatchStore later = new LatchStore(laterId);
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(
                        new FederatedEmbeddingStore.NamedStore(fastId, fast),
                        new FederatedEmbeddingStore.NamedStore(currentId, current),
                        new FederatedEmbeddingStore.NamedStore(laterId, later)),
                new TopicRoutingSettings(Map.of(
                        "default", Map.of(fastId, 1.0d, currentId, 1.0d, laterId, 1.0d)), 1),
                30_000,
                2);
        CountDownLatch callerDone = new CountDownLatch(1);
        AtomicReference<EmbeddingSearchResult<TextSegment>> outcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> callerDiagnostics = new AtomicReference<>();
        AtomicBoolean callerInterruptedAfterReturn = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try {
                outcome.set(store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                        .maxResults(3)
                        .minScore(0.0)
                        .build()));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                callerDiagnostics.set(String.valueOf(TraceStore.get("vector.federated.diagnostics")));
                callerInterruptedAfterReturn.set(Thread.currentThread().isInterrupted());
                TraceStore.clear();
                callerDone.countDown();
            }
        }, "federated-interrupt-caller");

        try {
            caller.start();
            assertTrue(fast.finished.await(2, TimeUnit.SECONDS));
            assertTrue(current.entered.await(2, TimeUnit.SECONDS));
            assertTrue(later.entered.await(2, TimeUnit.SECONDS));

            caller.interrupt();

            assertTrue(callerDone.await(2, TimeUnit.SECONDS),
                    "caller must stop before either blocked worker is released");
            caller.join(2_000L);
            assertFalse(caller.isAlive());
            assertNull(failure.get());
            assertTrue(callerInterruptedAfterReturn.get());
            assertEquals(1, outcome.get().matches().size());
            assertEquals(fastId + " doc", outcome.get().matches().get(0).embedded().text());
            assertFalse(current.interrupted.get());
            assertFalse(later.interrupted.get());

            String diagnostics = callerDiagnostics.get();
            String interruptionBreadcrumb = "interrupted:cancelMode=no_interrupt";
            assertEquals(1L, Pattern.compile(Pattern.quote(interruptionBreadcrumb))
                    .matcher(diagnostics)
                    .results()
                    .count());
            assertFalse(diagnostics.contains(fastId));
            assertFalse(diagnostics.contains(currentId));
            assertFalse(diagnostics.contains(laterId));
        } finally {
            current.release.countDown();
            later.release.countDown();
            current.finished.await(2, TimeUnit.SECONDS);
            later.finished.await(2, TimeUnit.SECONDS);
            caller.join(2_000);
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(2_000);
            }
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void workerSideInterruptedFailureDoesNotMarkCallerInterrupted() {
        TraceStore.clear();
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("worker", new FailingStore("worker"))),
                new TopicRoutingSettings(Map.of("default", Map.of("worker", 1.0d)), 1),
                100,
                1);

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(1)
                    .minScore(0.0)
                    .build());

            assertTrue(result.matches().isEmpty());
            assertFalse(Thread.currentThread().isInterrupted());
            assertFalse(String.valueOf(TraceStore.get("vector.federated.diagnostics"))
                    .contains("interrupted:cancelMode=no_interrupt"));
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void escapedWorkerErrorLeavesFixedStoreDiagnostic() {
        TraceStore.clear();
        String rawError = "private-worker-error-payload";
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("error", new ErrorStore(rawError))),
                new TopicRoutingSettings(Map.of("default", Map.of("error", 1.0d)), 1),
                100,
                1);

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(searchRequest(1, null));

            assertTrue(result.matches().isEmpty());
            String diagnostics = String.valueOf(TraceStore.get("vector.federated.diagnostics"));
            assertTrue(diagnostics.contains("worker_failed"));
            assertFalse(diagnostics.contains(rawError));
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void topicTraceDoesNotStoreRawFilterTopic() {
        TraceStore.clear();
        String rawSecret = "fixture-vector-topic-secret-1234567890";
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore("fast", new StaticStore("fast", 0))),
                new TopicRoutingSettings(Map.of("default", Map.of("fast", 1.0d)), 1),
                100,
                1);
        Filter rawTopicFilter = new Filter() {
            @Override
            public boolean test(Object object) {
                return true;
            }

            @Override
            public String toString() {
                return "MetadataFilter { key = 'topic', condition = EQUAL_TO, value = 'private vector topic api_key="
                        + rawSecret + "' }";
            }
        };

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(1)
                    .minScore(0.0)
                    .filter(rawTopicFilter)
                    .build());

            assertEquals(1, result.matches().size());
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(rawSecret), trace);
            assertFalse(String.valueOf(TraceStore.get("vector.federated.topic")).contains("private vector topic"));
            assertTrue(String.valueOf(TraceStore.get("vector.federated.topic")).startsWith("hash:"));
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    @Test
    void traceDoesNotStoreRawConfiguredStoreIds() {
        TraceStore.clear();
        String rawStoreId = "tenant-vector-store token=private-vector-store";
        FederatedEmbeddingStore store = new FederatedEmbeddingStore(
                List.of(new FederatedEmbeddingStore.NamedStore(rawStoreId, new StaticStore("fast", 0))),
                new TopicRoutingSettings(Map.of("default", Map.of(rawStoreId, 1.0d)), 1),
                100,
                1);

        try {
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                    .maxResults(1)
                    .minScore(0.0)
                    .build());

            assertEquals(1, result.matches().size());
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(rawStoreId), trace);
            assertFalse(trace.contains("private-vector-store"), trace);
            assertTrue(trace.contains("hash:"), trace);
        } finally {
            store.shutdownPool();
            TraceStore.clear();
        }
    }

    private static class StaticStore implements EmbeddingStore<TextSegment> {
        private final String id;
        private final long delayMs;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);

        private StaticStore(String id, long delayMs) {
            this.id = id;
            this.delayMs = delayMs;
        }

        @Override
        public String add(Embedding embedding) {
            return id;
        }

        @Override
        public void add(String id, Embedding embedding) {
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            return id;
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return Collections.emptyList();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
            return Collections.emptyList();
        }

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            started.countDown();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return new EmbeddingSearchResult<>(List.of());
                } finally {
                    finished.countDown();
                }
            } else {
                finished.countDown();
            }
            TextSegment segment = TextSegment.from(id + " doc", Metadata.from(Map.of("source", id)));
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            matches.add(new EmbeddingMatch<>(0.9, id + "-1", request.queryEmbedding(), segment));
            return new EmbeddingSearchResult<>(matches);
        }
    }

    private static final class LatchStore extends StaticStore {
        private final String id;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);

        private LatchStore(String id) {
            super(id, 0);
            this.id = id;
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return new EmbeddingSearchResult<>(List.of());
            } finally {
                finished.countDown();
            }
            TextSegment segment = TextSegment.from(id + " doc", Metadata.from(Map.of("source", id)));
            return new EmbeddingSearchResult<>(List.of(
                    new EmbeddingMatch<>(0.8, id + "-1", request.queryEmbedding(), segment)));
        }
    }

    private static final class FailingStore extends StaticStore {
        private FailingStore(String id) {
            super(id, 0);
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            throw new RuntimeException(new InterruptedException("fixture-worker-interruption"));
        }
    }

    private static final class ErrorStore extends StaticStore {
        private final String rawError;

        private ErrorStore(String rawError) {
            super("error", 0);
            this.rawError = rawError;
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            throw new AssertionError(rawError);
        }
    }

    private static final class NonInterruptibleLatchStore extends StaticStore {
        private final String id;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final ConcurrentLinkedQueue<CountDownLatch> completions = new ConcurrentLinkedQueue<>();

        private NonInterruptibleLatchStore(String id) {
            super(id, 0);
            this.id = id;
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            CountDownLatch completion = new CountDownLatch(1);
            completions.add(completion);
            invocations.incrementAndGet();
            entered.countDown();
            try {
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(1, TimeUnit.DAYS);
                    } catch (InterruptedException ignored) {
                        // This fixture deliberately models a provider that ignores cancellation.
                    }
                }
                TextSegment segment = TextSegment.from(id + " doc", Metadata.from(Map.of("source", id)));
                return new EmbeddingSearchResult<>(List.of(
                        new EmbeddingMatch<>(0.8d, id + "-1", request.queryEmbedding(), segment)));
            } finally {
                completion.countDown();
            }
        }

        private void releaseAndAwaitCurrentWorkers() throws InterruptedException {
            release.countDown();
            for (CountDownLatch completion : List.copyOf(completions)) {
                assertTrue(completion.await(1, TimeUnit.SECONDS));
            }
        }
    }

    private static EmbeddingSearchRequest searchRequest(int maxResults, Filter filter) {
        return EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[] { 1.0f }))
                .maxResults(maxResults)
                .minScore(0.0d)
                .filter(filter)
                .build();
    }

    private static Filter topicFilter(String topic) {
        return new Filter() {
            @Override
            public boolean test(Object object) {
                return true;
            }

            @Override
            public String toString() {
                return "MetadataFilter { key = 'topic', condition = EQUAL_TO, value = '" + topic + "' }";
            }
        };
    }
}
