package com.example.lms.diag;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalDiagnosticsCollectorTest {

    @Test
    void resetClearsCurrentThreadSpans() {
        RetrievalDiagnosticsCollector collector = new RetrievalDiagnosticsCollector();

        collector.withSpan("first", () -> List.of("a"));
        assertTrue(collector.dump().contains("first"));

        collector.reset();
        collector.withSpan("second", () -> List.of("b"));

        String dump = collector.dump();
        assertTrue(dump.contains("second"));
        assertFalse(dump.contains("first"));
    }

    @Test
    void spanErrorsUseRedactedLabelsInsteadOfRawExceptionMessages() throws Exception {
        RetrievalDiagnosticsCollector collector = new RetrievalDiagnosticsCollector();
        String raw = "failed at C:\\Users\\nninn\\Desktop\\secret\\rag.txt ownerToken=" + com.example.lms.test.SecretFixtures.openAiKey() + "";

        assertThrows(RuntimeException.class, () -> collector.withSpan("vector", () -> {
            throw new RuntimeException(raw);
        }));

        String rendered = String.valueOf(firstCompletedSpan(collector).getErrors());

        assertTrue(rendered.contains("RuntimeException:"), rendered);
        assertTrue(rendered.contains("hash:"), rendered);
        assertFalse(rendered.contains(raw));
        assertFalse(rendered.contains("C:\\Users\\nninn"));
        assertFalse(rendered.contains("ownerToken"));
        assertFalse(rendered.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
    }

    @SuppressWarnings("unchecked")
    private static StageSpan firstCompletedSpan(RetrievalDiagnosticsCollector collector) throws Exception {
        Field completed = RetrievalDiagnosticsCollector.class.getDeclaredField("completed");
        completed.setAccessible(true);
        ThreadLocal<List<StageSpan>> spans = (ThreadLocal<List<StageSpan>>) completed.get(collector);
        return spans.get().get(0);
    }
}
