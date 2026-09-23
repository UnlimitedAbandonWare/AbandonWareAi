package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceSnapshotFilterTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void exportsTraceAfterServletChainCompletes() throws Exception {
        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        TraceSnapshotFilter filter = new TraceSnapshotFilter(provider(exporter));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader("X-Request-Id", "rid-filter-001");
        request.addHeader("X-Session-Id", "sid-filter-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> TraceStore.put("cfvm.snapshot.saved", true);

        filter.doFilter(request, response, chain);

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        assertTrue(Files.exists(file));
        String body = Files.readString(file);
        assertTrue(body.contains("cfvm.snapshot.saved"));
        assertTrue(body.contains("_requestHash"));
        assertTrue(body.contains("_sessionHash"));
    }

    private static ObjectProvider<TraceSnapshotExporter> provider(TraceSnapshotExporter exporter) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TraceSnapshotExporter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(exporter);
        return provider;
    }
}
