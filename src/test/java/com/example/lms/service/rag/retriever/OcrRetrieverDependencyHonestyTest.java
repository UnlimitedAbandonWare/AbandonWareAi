package com.example.lms.service.rag.retriever;

import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class OcrRetrieverDependencyHonestyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void enabledOcrWithoutDelegateReturnsEmptyButRecordsMissingDependency() {
        OcrRetriever retriever = new OcrRetriever(emptyDelegateProvider(), true, false, 6, 1200);

        List<Content> result = retriever.retrieve(new Query("ocr dependency honesty"));

        assertTrue(result.isEmpty());
        assertEquals("missing_delegate", TraceStore.get("retrieval.dependency.ocr.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dependency.ocr.attempted"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dependency.ocr.fallbackUsed"));
        assertEquals("missing-dependency", TraceStore.get("retrieval.dependency.ocr.failureClass"));
    }

    @Test
    void requiredOcrWithoutDelegateFailsFastDuringStartupValidation() {
        OcrRetriever retriever = new OcrRetriever(emptyDelegateProvider(), true, true, 6, 1200);

        IllegalStateException ex = assertThrows(IllegalStateException.class, retriever::validateRequiredDependency);

        assertTrue(ex.getMessage().contains("rag.ocr.required=true"));
        assertTrue(ex.getMessage().contains("OcrRetriever bean is missing"));
    }

    @Test
    void delegateCancellationReturnsEmptyAndRecordsCancelledFailureClassWithoutRawLeak() {
        String rawQuery = "private ocr cancellation query";
        String rawToken = "ownerToken abc123";
        com.abandonware.ai.addons.ocr.OcrRetriever delegate =
                new com.abandonware.ai.addons.ocr.OcrRetriever(
                        new com.abandonware.ai.addons.config.AddonsProperties(),
                        (query, topK) -> List.of()) {
                    @Override
                    public List<com.abandonware.ai.addons.synthesis.ContextItem> retrieve(String query) {
                        throw new CancellationException("cancelled " + rawToken);
                    }
                };
        OcrRetriever retriever = new OcrRetriever(delegateProvider(delegate), true, false, 6, 1200);

        List<Content> result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.dependency.ocr.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dependency.ocr.attempted"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dependency.ocr.fallbackUsed"));
        assertEquals("cancelled", TraceStore.get("retrieval.dependency.ocr.failureClass"));
        assertEquals(List.of("cancelled"), TraceStore.get("ocr.fail"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery));
        assertFalse(trace.contains(rawToken));
    }

    @Test
    void valueKeysPreferCanonicalKebabCaseWithCamelCaseFallback() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/retriever/OcrRetriever.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("@Value(\"${rag.ocr.top-k:${rag.ocr.topK:6}}\")"));
        assertTrue(source.contains("@Value(\"${rag.ocr.max-chars:${rag.ocr.maxChars:1200}}\")"));
        assertFalse(source.contains("@Value(\"${rag.ocr.topK:6}\")"));
        assertFalse(source.contains("@Value(\"${rag.ocr.maxChars:1200}\")"));
    }

    private static ObjectProvider<com.abandonware.ai.addons.ocr.OcrRetriever> emptyDelegateProvider() {
        return new DefaultListableBeanFactory().getBeanProvider(com.abandonware.ai.addons.ocr.OcrRetriever.class);
    }

    private static ObjectProvider<com.abandonware.ai.addons.ocr.OcrRetriever> delegateProvider(
            com.abandonware.ai.addons.ocr.OcrRetriever delegate) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("ocrDelegate", delegate);
        return beanFactory.getBeanProvider(com.abandonware.ai.addons.ocr.OcrRetriever.class);
    }
}
