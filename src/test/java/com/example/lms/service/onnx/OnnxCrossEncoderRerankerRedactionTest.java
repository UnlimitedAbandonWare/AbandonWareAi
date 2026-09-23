package com.example.lms.service.onnx;

import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OnnxCrossEncoderRerankerRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void summarizeMasksSecretLikeThrowableMessages() throws Exception {
        String rawKey = "sk-redact123";
        Method summarize = OnnxCrossEncoderReranker.class.getDeclaredMethod("summarize", Throwable.class);
        summarize.setAccessible(true);

        String summary = String.valueOf(summarize.invoke(null,
                new IllegalStateException("Authorization Bearer " + rawKey + " failed")));

        assertFalse(summary.contains(rawKey));
        assertFalse(summary.contains("Bearer " + rawKey));
    }

    @Test
    void onnxCrossEncoderRerankerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "onnx reranker fail-soft paths need redacted breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void timeBudgetCancelSignalSkipsExpensiveRerankBeforeRuntimeReadinessCheck() {
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(new OnnxRuntimeService());
        ReflectionTestUtils.setField(reranker, "enabled", true);
        TraceStore.put("zero100.timeBudget.forceFallback", true);
        TraceStore.put("zero100.timeBudget.forceFallback.reason", "time_budget_force_fallback");

        List<Content> out = reranker.rerank("query", List.of(
                Content.from("alpha"),
                Content.from("beta"),
                Content.from("gamma")), 2);

        assertEquals(2, out.size());
        assertEquals("alpha", out.get(0).textSegment().text());
        assertEquals("beta", out.get(1).textSegment().text());
        assertEquals("time_budget_force_fallback", TraceStore.get("rerank.onnx.skipReason"));
    }
}
