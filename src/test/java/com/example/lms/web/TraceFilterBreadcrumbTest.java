package com.example.lms.web;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TraceFilterBreadcrumbTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        TraceContext.cleanupCurrentThread();
        MDC.clear();
    }

    @Test
    void requestInstallsTraceContextAndMlaBreadcrumbsDuringChain() throws ServletException, IOException {
        TraceFilter filter = new TraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        String rawRid = "sk-" + "tracefilterrequestid01234567890";
        String ridHash = SafeRedactor.hashValue(rawRid);
        request.addHeader("X-Request-Id", rawRid);
        request.addHeader("X-Session-Id", "sid-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean observed = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> {
            assertEquals(rawRid, MDC.get("x-request-id"));
            assertEquals("sid-123", MDC.get("sessionId"));
            assertEquals(ridHash, TraceStore.get("trace.id"));
            assertEquals(ridHash, TraceStore.get("x-request-id"));
            assertEquals(ridHash, TraceStore.get("requestId"));
            assertEquals(ridHash, TraceStore.get("rid"));
            assertEquals(ridHash, TraceStore.get("trace"));
            assertEquals(ridHash, TraceStore.get("traceId"));
            assertEquals(SafeRedactor.hashValue("sid-123"), TraceStore.get("sid"));
            assertEquals(SafeRedactor.hashValue("sid-123"), TraceStore.get("sessionId"));
            List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
            Map<?, ?> first = assertInstanceOf(Map.class, breadcrumbs.get(0));
            assertEquals(ridHash, first.get("requestId"));
            assertEquals(SafeRedactor.hashValue("sid-123"), first.get("sessionId"));
            Map<?, ?> data = assertInstanceOf(Map.class, first.get("data"));
            assertEquals(Boolean.TRUE, data.get("queryRedacted"));
            assertEquals("POST", data.get("method"));
            assertEquals(SafeRedactor.hashValue("/api/chat"), data.get("pathHash"));
            assertEquals("/api/chat".length(), data.get("pathLength"));
            assertFalse(data.containsKey("path"));
            assertFalse(String.valueOf(TraceStore.context()).contains(rawRid));
            assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
            assertEquals("request_started", TraceContext.current().getFlag("ml.request.decision"));
            observed.set(true);
        });

        assertTrue(observed.get());
        assertEquals(rawRid, response.getHeader("X-Request-Id"));
        assertNull(MDC.get("x-request-id"));
        assertNull(TraceStore.get("requestId"));
    }

    @Test
    void requestCapturesSnapshotBeforeTraceStoreCleanup() throws ServletException, IOException {
        TraceFilter filter = new TraceFilter();
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        ReflectionTestUtils.setField(filter, "traceSnapshotStore", snapshotStore);

        doAnswer(invocation -> {
            assertEquals("DEFAULT", TraceStore.get("retrievalOrder.lastSetBy"));
            assertEquals(0, TraceStore.get("outCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
            List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
            assertTrue(breadcrumbs.size() >= 2);
            return "snap-harmony-001";
        }).when(snapshotStore).captureCurrent(
                eq("http_request"),
                eq("POST"),
                eq("/api/chat"),
                eq(200),
                isNull()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("X-Request-Id", "rid-harmony-snapshot");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            TraceStore.put("retrievalOrder.lastSetBy", "DEFAULT");
            TraceStore.put("outCount", 0);
        });

        assertEquals("snap-harmony-001", response.getHeader("X-Trace-Snapshot-Id"));
        assertNull(TraceStore.get("retrievalOrder.lastSetBy"));
        assertNull(TraceStore.get("outCount"));
        verify(snapshotStore).captureCurrent(
                eq("http_request"),
                eq("POST"),
                eq("/api/chat"),
                eq(200),
                isNull()
        );
    }

    @Test
    void boostReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/web/TraceFilter.java"));

        assertFalse(source.contains("TraceStore.put(\"dbg.search.boost.reason\", boostReason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"dbg.search.boost.reason\", SafeRedactor.safeMessage(boostReason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"dbg.search.boost.reason\", SafeRedactor.traceLabelOrFallback(boostReason, \"unknown\"));"));
    }

    @Test
    void traceFilterDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/web/TraceFilter.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "TraceFilter fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("traceSuppressed(\"requestIds.traceStore\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"requestIds.responseHeader\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugBoost.state\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugKnobs.traceStore\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugSource.traceStore\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugHeaders\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"snapshot.capture\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"safeStatus\", ignore);"));
        assertTrue(source.contains("com.example.lms.search.TraceStore.put(\"trace.filter.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("com.example.lms.search.TraceStore.put(\"trace.filter.suppressed.errorType\", errorType);"));
    }
}
