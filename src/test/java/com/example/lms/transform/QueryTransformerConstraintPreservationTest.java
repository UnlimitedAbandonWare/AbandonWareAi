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
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@Timeout(10)
class QueryTransformerConstraintPreservationTest {
    @AfterEach void clear() { TraceStore.clear(); GuardContextHolder.clear(); }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Java 17 LangChain4j 1.0.1 integration guide|Java LangChain4j integration guide",
        "A not B deployment guide|B deployment guide",
        "A가 아니라 B 배포 방법|A 배포 방법",
        "site:dept.ac.kr tuition after:2025-01-01 before:2025-12-31 -scholarship lang:ko region:kr scope:public|tuition costs",
        "한국어 자료만 검색하고 개인정보는 제외해|개인정보 자료 검색"
    })
    void correctionAndExpansionCannotRelaxExplicitConstraints(String original, String relaxed) throws Exception {
        try (Fixture fixture = new Fixture(relaxed, relaxed)) {
            List<String> result = fixture.transformer.transform("", original);
            assertEquals(List.of(original), result);
        }
    }

    @Test void enhancedHintsCannotRemoveSearchScope() throws Exception {
        String original = "site:dept.ac.kr tuition after:2025 -scholarship lang:ko region:kr";
        try (Fixture fixture = new Fixture(original, "tuition cost")) {
            List<String> result = fixture.transformer.transformEnhanced(original, null);
            assertFalse(result.isEmpty());
            assertTrue(result.stream().allMatch(q -> q.contains("site:dept.ac.kr")
                    && q.contains("after:2025") && q.contains("-scholarship")
                    && q.contains("lang:ko") && q.contains("region:kr")));
        }
    }

    @Test void validSynonymWithConstraintsRemainsAvailable() throws Exception {
        String original = "site:dept.ac.kr tuition fee after:2025";
        String synonym = "site:dept.ac.kr tuition cost after:2025";
        try (Fixture fixture = new Fixture(original, synonym)) {
            assertTrue(fixture.transformer.transform("", original).contains(synonym));
        }
    }

    @Test void unconstrainedCorrectionStillWorksAndBlankDuplicateVariantsStayOut() throws Exception {
        try (Fixture fixture = new Fixture("explain quick car engine", "explain quick car engine\n\nexplain quick car engine")) {
            List<String> result = fixture.transformer.transform("", "explain fast car engine");
            assertTrue(result.contains("explain quick car engine"));
            assertTrue(result.stream().noneMatch(String::isBlank));
            assertEquals(result.size(), result.stream().distinct().count());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final QueryTransformer transformer;
        Fixture(String correction, String variant) {
            ChatModel model = mock(ChatModel.class);
            AtomicInteger calls = new AtomicInteger();
            when(model.chat(anyList())).thenAnswer(call -> ChatResponse.builder()
                    .aiMessage(AiMessage.from(calls.incrementAndGet() == 1 ? correction : variant)).build());
            transformer = new QueryTransformer(model);
            ReflectionTestUtils.setField(transformer, "llmFastExecutor", executor);
            ReflectionTestUtils.setField(transformer, "novaOrchEnabled", true);
            ReflectionTestUtils.setField(transformer, "novaOrchQueryTransformerEnabled", true);
            ReflectionTestUtils.setField(transformer, "llmTimeoutMsHint", 10_000L);
            ReflectionTestUtils.setField(transformer, "llmHintTimeoutFloorMs", 0L);
            ReflectionTestUtils.setField(transformer, "inflightTimeoutMs", 30_000L);
        }
        public void close() throws Exception {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
