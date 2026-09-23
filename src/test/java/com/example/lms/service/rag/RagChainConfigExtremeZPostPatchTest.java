package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.burst.ExtremeZProperties;
import com.example.lms.service.rag.burst.ExtremeZSystemHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagChainConfigExtremeZPostPatchTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void extremeZBeanWrapsRawSearchExecutorWithCancelShield() {
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        try {
            ObjectProvider<ExecutorService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(rawExecutor);
            RagChainConfig config = new RagChainConfig(null, null, null);

            ExtremeZSystemHandler handler = config.extremeZSystemHandler(
                    null, null, null, null, null, null, new ExtremeZProperties(), null, provider);

            assertNotNull(handler);
            assertEquals(Boolean.TRUE, TraceStore.get("extremez.executor.cancelShieldWrapped"));
            assertEquals("searchIoExecutor", TraceStore.get("extremeZ.executor.source"));
            assertEquals(Boolean.TRUE, TraceStore.get("extremez.systemHandler.executorWired"));
        } finally {
            rawExecutor.shutdownNow();
        }
    }

    @Test
    void extremeZBeanRecordsSequentialExecutorSourceWhenNoSearchExecutorIsAvailable() {
        RagChainConfig config = new RagChainConfig(null, null, null);

        ExtremeZSystemHandler handler = config.extremeZSystemHandler(
                null, null, null, null, null, null, new ExtremeZProperties(), null, null);

        assertNotNull(handler);
        assertEquals("sequential", TraceStore.get("extremeZ.executor.source"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.executor.cancelShieldWrapped"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.cancelShieldWrapped"));
        assertEquals("no_executor_sequential", TraceStore.get("extremeZ.cancelShield.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.systemHandler.executorWired"));
    }

    @Test
    void extremeZBeanKeepsClassLevelFormattingInsideConfigurationClass() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/RagChainConfig.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "\n    @Bean\n    public ExtremeZSystemHandler extremeZSystemHandler("));
        assertFalse(source.contains(
                "\n@Bean\npublic com.example.lms.service.rag.burst.ExtremeZSystemHandler extremeZSystemHandler("));
    }
}
