package com.example.lms.service.embedding;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoratingEmbeddingModelTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void embeddingTraceSuppressionsNormalizeNumericErrorType() {
        EmbeddingTraceSuppressions.trace(
                "decorator.incParseTrace",
                new NumberFormatException("ownerToken=raw-secret"));

        assertEquals("invalid_number",
                TraceStore.get("embed.suppressed.decorator.incParseTrace.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("ownerToken=raw-secret"), trace);
    }

    @Test
    void embedAllUsesDelegateBatchForCacheMissesAndThenServesCacheHits() {
        CountingEmbeddingModel delegate = new CountingEmbeddingModel();
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                delegate,
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            segments.add(TextSegment.from("segment-" + i));
        }

        List<Embedding> first = model.embedAll(segments).content();
        List<Embedding> second = model.embedAll(segments).content();

        assertEquals(20, first.size());
        assertEquals(20, second.size());
        assertEquals(1, delegate.batchCalls.get());
        assertEquals(0, delegate.singleCalls.get());
    }

    @Test
    void decoratingEmbeddingModelDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/DecoratingEmbeddingModel.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "embedding cache decorator fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void embeddingCacheDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/EmbeddingCache.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "embedding cache fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void embeddingFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String decorator = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/DecoratingEmbeddingModel.java"));
        String cache = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/EmbeddingCache.java"));
        String suppressions = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/EmbeddingTraceSuppressions.java"));

        assertTrue(suppressions.contains("[Embedding] suppressed trace failed stage={} errorType={}"));

        assertEmbeddingStage(decorator, "decorator.batchStatsTrace");
        assertEmbeddingStage(decorator, "decorator.batchFailoverResetTrace");
        assertEmbeddingStage(decorator, "decorator.batchEmbedMisses");
        assertEmbeddingStage(decorator, "decorator.batchErrorTrace");
        assertEmbeddingStage(decorator, "decorator.batchFailoverReadTrace");
        assertEmbeddingStage(decorator, "decorator.batchFailoverInvalidateTrace");
        assertEmbeddingStage(decorator, "decorator.batchFailoverTraceEvent");
        assertEmbeddingStage(decorator, "decorator.singleFailoverResetTrace");
        assertEmbeddingStage(decorator, "decorator.singleCompute");
        assertEmbeddingStage(decorator, "decorator.singleErrorTrace");
        assertEmbeddingStage(decorator, "decorator.singleFailoverReadTrace");
        assertEmbeddingStage(decorator, "decorator.singleStatsTrace");
        assertEmbeddingStage(decorator, "decorator.cacheStatsTrace");
        assertEmbeddingStage(decorator, "decorator.singleFailoverLastTrace");
        assertEmbeddingStage(decorator, "decorator.singleFailoverSnapshotTrace");
        assertEmbeddingStage(decorator, "decorator.singleFailoverTraceEvent");
        assertEmbeddingStage(decorator, "decorator.cacheKeyMetadataTrace");
        assertEmbeddingStage(decorator, "decorator.dbgSearchTrace");
        assertEmbeddingInvalidNumberStage(decorator, "decorator.incParseTrace");
        assertEmbeddingStage(decorator, "decorator.incTrace");

        assertCacheStage(cache, "keyFor.sha256");
        assertCacheStage(cache, "keyForV2.sha256");
        assertCacheStage(cache, "blankKey.compute");
        assertCacheStage(cache, "singleFlight.wait");
        assertCacheStage(cache, "leader.compute");
        assertCacheStage(cache, "singleFlight.complete");
        assertCacheStage(cache, "invalidate.complete");
    }

    @Test
    void counterParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/embedding/DecoratingEmbeddingModel.java"));
        String parserCall = "n = Long.parseLong(String.valueOf(v).trim());";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "counter parser call should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(window.contains("catch (Exception"),
                "counter parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "counter parser must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "counter parser should only catch NumberFormatException");
    }

    @Test
    void singleEmbedErrorTraceRedactsThrownMessage() {
        TraceStore.put("dbg.search.enabled", true);
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                new ThrowingSingleEmbeddingModel(),
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));

        model.embed("hello");

        String trace = String.valueOf(TraceStore.get("embed.error.cur"));
        assertNotNull(trace);
        assertTrue(trace.contains("errorHash="), trace);
        assertTrue(trace.contains("errorLength="), trace);
        assertFalse(trace.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(trace.contains("Bearer " + "raw-embedding-token"));
        assertFalse(trace.contains("embedding failed"), trace);
        assertFalse(trace.contains("RuntimeException"), trace);
    }

    @Test
    void batchEmbedErrorTraceRedactsThrownMessage() {
        TraceStore.put("dbg.search.enabled", true);
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                new ThrowingBatchEmbeddingModel(),
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));

        model.embedAll(List.of(TextSegment.from("hello")));

        String trace = String.valueOf(TraceStore.get("embed.batch.error.cur"));
        assertNotNull(trace);
        assertTrue(trace.contains("errorHash="), trace);
        assertTrue(trace.contains("errorLength="), trace);
        assertFalse(trace.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(trace.contains("Bearer " + "raw-embedding-token"));
        assertFalse(trace.contains("embedding failed"), trace);
        assertFalse(trace.contains("RuntimeException"), trace);
    }

    @Test
    void batchFallbackStopsAfterThreeConsecutiveSingleComputeFailures() {
        TraceStore.put("dbg.search.enabled", true);
        BatchAndSingleFailingEmbeddingModel delegate = new BatchAndSingleFailingEmbeddingModel();
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                delegate,
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            segments.add(TextSegment.from("segment-" + i));
        }

        List<Embedding> result = model.embedAll(segments).content();

        assertEquals(16, result.size());
        assertEquals(1, delegate.batchCalls.get());
        assertEquals(3, delegate.singleCalls.get(),
                "consecutive unrecovered failures must stop the remaining per-item fallback attempts");
        assertTrue(result.stream().allMatch(embedding -> embedding.vector().length == 0));
        assertEquals(Boolean.TRUE, TraceStore.get("embed.batch.perItemFallback.stopped"));
        assertEquals(13, TraceStore.get("embed.batch.perItemFallback.remaining"));
    }

    @Test
    void batchFallbackContinuesAfterOneItemSpecificFailureAndClearsStopTrace() {
        TraceStore.put("embed.batch.perItemFallback.stopped", true);
        TraceStore.put("embed.batch.perItemFallback.remaining", 99);
        LeadingSingleFailuresEmbeddingModel delegate = new LeadingSingleFailuresEmbeddingModel(1);
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                delegate,
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            segments.add(TextSegment.from("segment-" + i));
        }

        List<Embedding> result = model.embedAll(segments).content();

        assertEquals(4, result.size());
        assertEquals(1, delegate.batchCalls.get());
        assertEquals(4, delegate.singleCalls.get(),
                "one item-specific failure must not suppress later recoverable embeddings");
        assertEquals(0, result.get(0).vector().length);
        assertTrue(result.subList(1, result.size()).stream()
                .allMatch(embedding -> embedding.vector().length > 0));
        assertEquals(Boolean.FALSE, TraceStore.get("embed.batch.perItemFallback.stopped"));
        assertEquals(0, TraceStore.get("embed.batch.perItemFallback.remaining"));
    }

    @Test
    void batchFallbackContinuesAfterTwoItemSpecificFailures() {
        LeadingSingleFailuresEmbeddingModel delegate = new LeadingSingleFailuresEmbeddingModel(2);
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                delegate,
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));
        List<TextSegment> segments = List.of(
                TextSegment.from("bad-0"),
                TextSegment.from("bad-1"),
                TextSegment.from("good-2"),
                TextSegment.from("good-3"));

        List<Embedding> result = model.embedAll(segments).content();

        assertEquals(4, delegate.singleCalls.get(),
                "two item-specific failures must not suppress later recoverable embeddings");
        assertEquals(0, result.get(0).vector().length);
        assertEquals(0, result.get(1).vector().length);
        assertTrue(result.subList(2, result.size()).stream()
                .allMatch(embedding -> embedding.vector().length > 0));
        assertEquals(Boolean.FALSE, TraceStore.get("embed.batch.perItemFallback.stopped"));
    }

    @Test
    void batchFallbackTraceResetsWhenNextBatchIsAllCacheHits() {
        CountingEmbeddingModel delegate = new CountingEmbeddingModel();
        DecoratingEmbeddingModel model = new DecoratingEmbeddingModel(
                delegate,
                new EmbeddingCache.InMemory(),
                Duration.ofMinutes(5));
        List<TextSegment> segments = List.of(TextSegment.from("cached-0"), TextSegment.from("cached-1"));
        model.embedAll(segments);
        TraceStore.put("embed.batch.perItemFallback.stopped", true);
        TraceStore.put("embed.batch.perItemFallback.remaining", 99);

        model.embedAll(segments);

        assertEquals(1, delegate.batchCalls.get(), "the second batch must be served from cache");
        assertEquals(Boolean.FALSE, TraceStore.get("embed.batch.perItemFallback.stopped"));
        assertEquals(0, TraceStore.get("embed.batch.perItemFallback.remaining"));
    }

    private static final class CountingEmbeddingModel implements EmbeddingModel {
        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger singleCalls = new AtomicInteger();

        @Override
        public Response<Embedding> embed(String text) {
            singleCalls.incrementAndGet();
            return Response.from(Embedding.from(vector(text)));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            singleCalls.incrementAndGet();
            return Response.from(Embedding.from(vector(textSegment == null ? "" : textSegment.text())));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            batchCalls.incrementAndGet();
            List<Embedding> out = new ArrayList<>();
            for (TextSegment segment : textSegments) {
                out.add(Embedding.from(vector(segment == null ? "" : segment.text())));
            }
            return Response.from(out);
        }

    }

    private static final class ThrowingSingleEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<Embedding> embed(String text) {
            throw secretFailure();
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            throw secretFailure();
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            throw secretFailure();
        }
    }

    private static final class ThrowingBatchEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(vector(text)));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return Response.from(Embedding.from(vector(textSegment == null ? "" : textSegment.text())));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            throw secretFailure();
        }
    }

    private static final class BatchAndSingleFailingEmbeddingModel implements EmbeddingModel {
        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger singleCalls = new AtomicInteger();

        @Override
        public Response<Embedding> embed(String text) {
            singleCalls.incrementAndGet();
            throw secretFailure();
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            singleCalls.incrementAndGet();
            throw secretFailure();
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            batchCalls.incrementAndGet();
            throw secretFailure();
        }
    }

    private static final class LeadingSingleFailuresEmbeddingModel implements EmbeddingModel {
        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger singleCalls = new AtomicInteger();
        private final int failureCount;

        private LeadingSingleFailuresEmbeddingModel(int failureCount) {
            this.failureCount = failureCount;
        }

        @Override
        public Response<Embedding> embed(String text) {
            return embed(TextSegment.from(text));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            if (singleCalls.incrementAndGet() <= failureCount) {
                throw secretFailure();
            }
            return Response.from(Embedding.from(new float[] { 1.0f, 0.5f }));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            batchCalls.incrementAndGet();
            throw secretFailure();
        }
    }

    private static RuntimeException secretFailure() {
        return new RuntimeException(
                "embedding failed " + com.example.lms.test.SecretFixtures.openAiKey() + " Authorization: Bearer " + "raw-embedding-token");
    }

    private static void assertEmbeddingStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[Embedding] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing embedding fail-soft stage: " + stage);
    }

    private static void assertEmbeddingInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[Embedding] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing embedding invalid_number fail-soft stage: " + stage);
    }

    private static void assertCacheStage(String source, String stage) {
        assertTrue(source.contains("LOG.log(System.Logger.Level.DEBUG, \"[EmbeddingCache] fail-soft stage={0}\", \"" + stage + "\")"),
                () -> "missing embedding cache fail-soft stage: " + stage);
    }

    private static float[] vector(String text) {
        return new float[] { Math.max(1, text == null ? 0 : text.length()) };
    }
}
