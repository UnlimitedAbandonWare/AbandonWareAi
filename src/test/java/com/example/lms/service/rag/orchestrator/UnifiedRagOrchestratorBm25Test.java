package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import com.example.lms.service.service.rag.bm25.Bm25Index;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedRagOrchestratorBm25Test {
    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    private static UnifiedRagOrchestrator.QueryResponse query(Bm25Index index) {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "bm25Index", index);
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "synthetic bm25 query";
        request.useWeb = request.useVector = request.useKg = false;
        request.enableQueryAnalysis = request.enableBiEncoder = request.enableOnnx = request.enableDiversity = false;
        return orchestrator.query(request);
    }

    @Test
    void bm25ExceptionIsFailedInsteadOfEmpty() {
        Bm25Index index = mock(Bm25Index.class);
        when(index.search(anyString(), anyInt())).thenThrow(new IllegalStateException("private failure detail"));
        var response = query(index);
        assertEquals("failed:IllegalStateException", response.debug.get("stage.bm25"));
        assertFalse(String.valueOf(response.debug).contains("private failure detail"));
        verify(index).search(anyString(), anyInt());
    }

    @Test
    void successfulZeroHitsRemainEmpty() {
        Bm25Index index = mock(Bm25Index.class);
        when(index.search(anyString(), anyInt())).thenReturn(List.of());
        assertEquals("empty", query(index).debug.get("stage.bm25"));
        verify(index).search(anyString(), anyInt());
    }

    @Test
    void successfulHitsKeepTheirCountAndSource() {
        Bm25Index index = mock(Bm25Index.class);
        when(index.search(anyString(), anyInt())).thenReturn(List.of(Map.entry("doc-1", 2.0)));
        assertEquals("ok:1", query(index).debug.get("stage.bm25"));
    }

    @Test
    void sourceContainsNoRawNulBytes() throws Exception {
        byte[] source = Files.readAllBytes(Path.of(
                "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"));
        for (byte value : source) {
            assertNotEquals(0, value, "raw NUL makes source search treat Java as binary");
        }
    }
}
