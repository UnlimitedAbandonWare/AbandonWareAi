package com.example.lms.service.telemetry;

import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionTraceEventTest {

    @Test
    void toJsonStoresRequestAndQueryAsHashOnlyDiagnostics() {
        DecisionTraceEvent event = new DecisionTraceEvent();
        event.requestId = "rid-user-raw-12345";
        event.query = "raw user query with api_key=abcdsecret";
        event.cell = "router-cell-a";

        String json = event.toJson();

        assertFalse(json.contains(event.requestId));
        assertFalse(json.contains(event.query));
        assertFalse(json.contains("abcdsecret"));
        assertFalse(json.contains("\"query\":\""));
        assertFalse(json.contains("\"requestId\":\""));
        assertTrue(json.contains("\"requestIdHash\":\"" + SafeRedactor.hashValue(event.requestId) + "\""));
        assertTrue(json.contains("\"queryHash\":\"" + SafeRedactor.hashValue(event.query) + "\""));
        assertTrue(json.contains("\"queryLength\":38"));
        assertTrue(json.contains("\"queryPresent\":true"));
    }
}
