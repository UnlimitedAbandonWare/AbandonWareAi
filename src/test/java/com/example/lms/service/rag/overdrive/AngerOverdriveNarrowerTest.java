package com.example.lms.service.rag.overdrive;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AngerOverdriveNarrowerTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void returnsTopEightAfterSuccessfulRerankAndTracesCounts() {
        List<Content> docs = docs(12);
        CrossEncoderReranker reranker = (query, candidates, topN) -> new ArrayList<>(candidates);
        AngerOverdriveNarrower narrower = new AngerOverdriveNarrower(reranker);

        List<Content> out = narrower.narrow("ranking query", docs);

        assertEquals(8, out.size());
        assertEquals(12, TraceStore.get("overdrive.narrow.input.count"));
        assertEquals(8, TraceStore.get("overdrive.narrow.output.count"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.narrow.failSoft"));
        assertEquals("reranked", TraceStore.get("overdrive.narrow.reason"));
        assertEquals(8, TraceStore.get("overdrive.anchor.narrowed.k"));
        assertEquals("reranked", TraceStore.get("overdrive.anchor.narrowedReason"));
        assertEquals("", TraceStore.get("overdrive.anchor.skipReason"));
    }

    @Test
    void rerankerFailureReturnsOriginalFailSoft() {
        List<Content> docs = docs(3);
        CrossEncoderReranker reranker = (query, candidates, topN) -> {
            throw new IllegalStateException("boom");
        };
        AngerOverdriveNarrower narrower = new AngerOverdriveNarrower(reranker);

        List<Content> out = narrower.narrow("ranking query", docs);

        assertSame(docs, out);
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.narrow.failSoft"));
        assertEquals("exception_original_returned", TraceStore.get("overdrive.narrow.reason"));
        assertEquals("exception_original_returned", TraceStore.get("overdrive.anchor.error"));
    }

    @Test
    void missingRerankerProviderPassesThroughWithoutBootHardRequirement() {
        List<Content> docs = docs(3);
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AngerOverdriveNarrower narrower = new AngerOverdriveNarrower(
                beanFactory.getBeanProvider(CrossEncoderReranker.class));

        List<Content> out = narrower.narrow("ranking query", docs);

        assertSame(docs, out);
        assertEquals("noop_passthrough", TraceStore.get("overdrive.narrow.reranker.source"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.narrow.failSoft"));
        assertEquals("reranker_missing_original_returned", TraceStore.get("overdrive.narrow.reason"));
        assertEquals(3, TraceStore.get("overdrive.anchor.narrowed.k"));
        assertEquals("reranker_missing_original_returned", TraceStore.get("overdrive.anchor.narrowedReason"));
        assertEquals("reranker_missing_original_returned", TraceStore.get("overdrive.anchor.skipReason"));
    }

    @Test
    void narrowReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/overdrive/AngerOverdriveNarrower.java"));

        assertFalse(source.contains("TraceStore.put(\"overdrive.narrow.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"overdrive.narrow.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.narrow.reason\", safeReason);"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.anchor.error\", safeReason);"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.anchor.narrowedReason\", safeReason);"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.anchor.skipReason\", failSoft ? safeReason : \"\");"));
        assertTrue(source.contains("stage=narrow.trace"));
        assertTrue(source.contains("stage=narrow.hash"));
    }

    private static List<Content> docs(int n) {
        List<Content> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Content.from("doc " + i));
        }
        return out;
    }
}
