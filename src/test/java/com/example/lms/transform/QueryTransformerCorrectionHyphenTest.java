package com.example.lms.transform;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@Timeout(5)
class QueryTransformerCorrectionHyphenTest {

    @AfterEach
    void clearRequestState() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "state-of-the-art retrieval",
            "cache-control max-age settings",
            "non-empty collection",
            "read-only user",
            "ISO-8601 timestamps",
            "대전-세종 노선",
            "latency 10-20 ms"
    })
    void completeCorrectionPreservesHyphenatedMeaning(String correction) throws Exception {
        try (Fixture fixture = new Fixture(correction)) {
            assertEquals(correction, fixture.correct("unpolished source wording"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Corrected: state-of-the-art retrieval | state-of-the-art retrieval",
            "교정： read-only access | read-only access",
            "wrong → non-empty collection | non-empty collection",
            "wrong -> zero-copy parsing | zero-copy parsing",
            "wrong > reference text | reference text"
    })
    void existingColonAndArrowLabelsKeepTheirEntireRightSide(String response, String expected) throws Exception {
        try (Fixture fixture = new Fixture(response)) {
            assertEquals(expected, fixture.correct("unpolished source wording"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void plainCorrectionRemainsUnchanged() throws Exception {
        try (Fixture fixture = new Fixture("precise retrieval wording")) {
            assertEquals("precise retrieval wording", fixture.correct("unpolished source wording"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void numberedLabelCleanupPreservesHyphenatedCorrection() throws Exception {
        try (Fixture fixture = new Fixture("1. Corrected: read-only access")) {
            assertEquals("read-only access", fixture.correct("unpolished source wording"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void repeatedCorrectionUsesExistingCacheWithoutChangingItsMeaning() throws Exception {
        try (Fixture fixture = new Fixture("read-only access")) {
            assertEquals("read-only access", fixture.correct("unpolished source wording"));
            assertEquals("read-only access", fixture.correct("unpolished source wording"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void publicTransformRetainsOriginalAndCompleteCorrectionAsSeparateQueries() throws Exception {
        try (Fixture fixture = new Fixture("state-of-the-art retrieval")) {
            String original = "unpolished source wording";
            List<String> queries = fixture.transformer.transform("", original);
            assertEquals(original, queries.get(0));
            assertTrue(queries.contains("state-of-the-art retrieval"));
            assertFalse(queries.contains("art retrieval"));
            assertEquals(2, fixture.calls.get(), "one correction and one independent variant response");
        }
    }

    @Test
    void modelFailureReturnsOriginalQuery() throws Exception {
        try (Fixture fixture = new Fixture("unused")) {
            fixture.fail = true;
            assertEquals("read-only original", fixture.correct("read-only original"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void disabledAuxReturnsOriginalWithoutCallingModel() throws Exception {
        try (Fixture fixture = new Fixture("unused")) {
            ReflectionTestUtils.setField(fixture.transformer, "novaOrchQueryTransformerEnabled", false);
            assertEquals("read-only original", fixture.correct("read-only original"));
            assertEquals(0, fixture.calls.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger calls = new AtomicInteger();
        final QueryTransformer transformer;
        boolean fail;

        Fixture(String correction) {
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(invocation -> {
                int number = calls.incrementAndGet();
                if (fail) {
                    throw new IllegalStateException("synthetic correction failure");
                }
                // Later variant output cannot repair or mask the correction under test.
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(number == 1 ? correction : "catalog index"))
                        .build();
            });
            transformer = new QueryTransformer(model);
            ReflectionTestUtils.setField(transformer, "llmFastExecutor", executor);
            ReflectionTestUtils.setField(transformer, "novaOrchEnabled", true);
            ReflectionTestUtils.setField(transformer, "novaOrchQueryTransformerEnabled", true);
            ReflectionTestUtils.setField(transformer, "llmTimeoutMsHint", 10_000L);
            ReflectionTestUtils.setField(transformer, "llmHintTimeoutFloorMs", 0L);
            ReflectionTestUtils.setField(transformer, "inflightTimeoutMs", 30_000L);
        }

        String correct(String original) {
            return ReflectionTestUtils.invokeMethod(transformer, "correctWithLLM", "", original);
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
}
