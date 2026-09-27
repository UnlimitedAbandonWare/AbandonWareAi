package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatWorkflowRequestTraceEnvelopeTest {

    @AfterEach
    void clearTraceState() {
        org.slf4j.MDC.clear();
        TraceStore.clear();
    }

    @Test
    void rehydratesCorrelationAliasesAsHashOnly() {
        String raw = "raw-workflow-request-correlation";

        ChatWorkflowRequestTraceEnvelope.rehydrateRequestCorrelation(raw);

        String expected = SafeRedactor.hashValue(raw);
        for (String key : List.of("trace.id", "x-request-id", "requestId", "traceId")) {
            assertEquals(expected, TraceStore.get(key));
        }
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
        ChatWorkflowRequestTraceEnvelope.rehydrateRequestCorrelation("   ");
        for (String key : List.of("trace.id", "x-request-id", "requestId", "traceId")) {
            assertNull(TraceStore.get(key));
        }
    }

    @Test
    void seedsRequestAndSessionBreadcrumbsWithoutRawTraceStoreIdentifiers() {
        ChatRequestDto req = new ChatRequestDto();
        req.setSessionId(42L);
        org.slf4j.MDC.put("sid", "raw-browser-request-sid");
        org.slf4j.MDC.put("traceId", "raw-request-trace-id");

        ChatWorkflowRequestTraceEnvelope.seed(req, "chat-42");

        assertEquals(SafeRedactor.hashValue("raw-browser-request-sid"), TraceStore.get("req.sid"));
        assertEquals(SafeRedactor.hashValue("42"), TraceStore.get("chatSessionHash"));
        assertEquals(SafeRedactor.hashValue("chat-42"), TraceStore.get("sid"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("raw-browser-request-sid"));
        assertFalse(trace.contains("raw-request-trace-id"));
    }

    @Test
    void preservesExistingMdcContract() {
        ChatRequestDto req = new ChatRequestDto();
        req.setSessionId(42L);
        org.slf4j.MDC.put("sid", "raw-browser-request-sid");

        ChatWorkflowRequestTraceEnvelope.seed(req, "chat-42");

        assertEquals("raw-browser-request-sid", org.slf4j.MDC.get("requestSid"));
        assertEquals("42", org.slf4j.MDC.get("chatSessionId"));
        assertEquals("chat-42", org.slf4j.MDC.get("sid"));
        assertEquals("chat-42", org.slf4j.MDC.get("sessionId"));
    }

    @Test
    void nullRequestLeavesSuppressionBreadcrumbsAndReturns() {
        ChatWorkflowRequestTraceEnvelope.seed(null, "chat-42");

        assertEquals(Boolean.TRUE, TraceStore.get("chat.workflow.suppressed.traceSeed.chatSessionId"));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.workflow.suppressed.traceSeed.breadcrumb"));
    }
}
