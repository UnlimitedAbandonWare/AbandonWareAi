package com.abandonware.ai.service.onnx;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxCrossEncoderRerankerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void disabledPathDoesNotAcquirePermit() throws Exception {
        OnnxCrossEncoderReranker reranker = reranker(false, true, readyTokenizer(), readyRuntime());
        Semaphore gate = gate(reranker);
        List<OnnxCrossEncoderReranker.ScoredDoc> candidates = docs();

        List<OnnxCrossEncoderReranker.ScoredDoc> out = reranker.rerank("query", candidates);

        assertSame(candidates, out);
        assertEquals(1, gate.availablePermits());
    }

    @Test
    void interruptedAcquireRestoresInterruptAndDoesNotOverRelease() throws Exception {
        OnnxCrossEncoderReranker reranker = reranker(true, true, readyTokenizer(), readyRuntime());
        Semaphore gate = gate(reranker);
        gate.acquire();

        Thread.currentThread().interrupt();
        try {
            List<OnnxCrossEncoderReranker.ScoredDoc> candidates = docs();
            List<OnnxCrossEncoderReranker.ScoredDoc> out = reranker.rerank("query", candidates);

            assertSame(candidates, out);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, gate.availablePermits());
            assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.abandonware.suppressed"));
            assertEquals("rerank.interrupted", TraceStore.get("rerank.onnx.abandonware.suppressed.stage"));
            assertEquals("cancelled", TraceStore.get("rerank.onnx.abandonware.suppressed.errorClass"));
        } finally {
            Thread.interrupted();
            gate.release();
        }
    }

    @Test
    void exceptionAfterAcquireReleasesPermit() throws Exception {
        OnnxCrossEncoderReranker reranker = reranker(true, true, readyTokenizer(), failingRuntime());
        Semaphore gate = gate(reranker);
        List<OnnxCrossEncoderReranker.ScoredDoc> candidates = docs();

        List<OnnxCrossEncoderReranker.ScoredDoc> out = reranker.rerank("query", candidates);

        assertSame(candidates, out);
        assertEquals(1, gate.availablePermits());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.abandonware.suppressed"));
        assertEquals("rerank.exception", TraceStore.get("rerank.onnx.abandonware.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("rerank.onnx.abandonware.suppressed.errorClass"));
    }

    @Test
    void failSoftCatchPathsKeepTraceHooks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java"));

        assertTrue(source.contains("traceSuppressed(\"rerankTopK\", t);"));
        assertTrue(source.contains("traceSuppressed(\"emit\", ignored);"));
    }

    private static OnnxCrossEncoderReranker reranker(
            boolean enabled,
            boolean ready,
            TokenizerAdapter tokenizer,
            OnnxRuntimeService runtime) {
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(1, 10);
        ReflectionTestUtils.setField(reranker, "enabled", enabled);
        ReflectionTestUtils.setField(reranker, "ort", ready ? runtime : notReadyRuntime());
        ReflectionTestUtils.setField(reranker, "tokenizer", tokenizer);
        return reranker;
    }

    private static List<OnnxCrossEncoderReranker.ScoredDoc> docs() {
        return List.of(new OnnxCrossEncoderReranker.ScoredDoc("a", "doc", 0.5d));
    }

    private static Semaphore gate(OnnxCrossEncoderReranker reranker) throws Exception {
        Field field = OnnxCrossEncoderReranker.class.getDeclaredField("gate");
        field.setAccessible(true);
        return (Semaphore) field.get(reranker);
    }

    private static TokenizerAdapter readyTokenizer() {
        return (queries, docs) -> new TokenizerAdapter.EncodedTriplet(
                new long[][]{{1L}},
                new long[][]{{1L}},
                new long[][]{{0L}});
    }

    private static OnnxRuntimeService readyRuntime() {
        return new RuntimeStub(true, false);
    }

    private static OnnxRuntimeService notReadyRuntime() {
        return new RuntimeStub(false, false);
    }

    private static OnnxRuntimeService failingRuntime() {
        return new RuntimeStub(true, true);
    }

    private static final class RuntimeStub extends OnnxRuntimeService {
        private final boolean ready;
        private final boolean fail;

        private RuntimeStub(boolean ready, boolean fail) {
            this.ready = ready;
            this.fail = fail;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public float[] scoreBatch(long[][] inputIds, long[][] attnMask, long[][] tokenTypeIds) {
            if (fail) {
                throw new IllegalStateException("synthetic failure");
            }
            return new float[]{0.7f};
        }
    }
}
