package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class ChatWorkflowRequestTraceEnvelope {

    private ChatWorkflowRequestTraceEnvelope() {
    }

    static void rehydrateRequestCorrelation(String rawCorrelationId) {
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return;
        }
        String correlationHash = SafeRedactor.hashValue(rawCorrelationId);
        if (correlationHash == null || correlationHash.isBlank()) {
            return;
        }
        TraceStore.put("trace.id", correlationHash);
        TraceStore.put("x-request-id", correlationHash);
        TraceStore.put("requestId", correlationHash);
        TraceStore.put("traceId", correlationHash);
    }

    static void seed(ChatRequestDto req, String sessionKey) {
        try {
            TraceStore.put("trace.runId", String.format(
                    "chat:%s:%d", SafeRedactor.hashValue(sessionKey), System.nanoTime()));

            String requestSid = null;
            try {
                requestSid = org.slf4j.MDC.get("sid");
                if (requestSid != null && !requestSid.isBlank()
                        && sessionKey != null && !requestSid.equals(sessionKey)) {
                    TraceStore.putIfAbsent("req.sid", SafeRedactor.hashValue(requestSid));
                    if (org.slf4j.MDC.get("requestSid") == null) {
                        org.slf4j.MDC.put("requestSid", requestSid);
                    }
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.requestSid", failure);
            }

            try {
                if (req.getSessionId() != null) {
                    TraceStore.put("chatSessionHash",
                            SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                    if (org.slf4j.MDC.get("chatSessionId") == null) {
                        org.slf4j.MDC.put("chatSessionId", String.valueOf(req.getSessionId()));
                    }
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.chatSessionId", failure);
            }

            String traceId = null;
            try {
                traceId = org.slf4j.MDC.get("traceId");
                if (traceId == null || traceId.isBlank()) {
                    traceId = org.slf4j.MDC.get("trace");
                }
                if (traceId == null || traceId.isBlank()) {
                    traceId = org.slf4j.MDC.get("x-request-id");
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcTraceId", failure);
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = java.util.UUID.randomUUID().toString();
            }
            rehydrateRequestCorrelation(traceId);

            if (sessionKey != null && !sessionKey.isBlank()) {
                TraceStore.put("sid", SafeRedactor.hashValue(sessionKey));
                try {
                    org.slf4j.MDC.put("sid", sessionKey);
                    org.slf4j.MDC.put("sessionId", sessionKey);
                } catch (Throwable failure) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcSession", failure);
                }
            }

            try {
                java.util.Map<String, Object> breadcrumb = new java.util.LinkedHashMap<>();
                breadcrumb.put("conversationSidHash", SafeRedactor.hashValue(sessionKey));
                breadcrumb.put("requestSidHash", SafeRedactor.hashValue(requestSid));
                breadcrumb.put("chatSessionHash",
                        SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                breadcrumb.put("traceIdHash", SafeRedactor.hashValue(traceId));
                ai.abandonware.nova.orch.trace.OrchEventEmitter.breadcrumb(
                        "conversation.breadcrumb.seed",
                        "Seeded conversation breadcrumb in MDC/TraceStore",
                        "ChatWorkflow.continueChat",
                        breadcrumb);
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.breadcrumb", failure);
            }
        } catch (Exception failure) {
            ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.envelope", failure);
        }
    }
}
