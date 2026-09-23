package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BraveMapperTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void malformedJsonRecordsRedactedParserFailure() {
        SearchBundle bundle = BraveMapper.toBundle("{\"secret\":\"raw private response\"");

        assertTrue(bundle.docs().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.mapper.parseFailed"));
        assertEquals("JsonEOFException", TraceStore.get("web.brave.mapper.parseFailureClass"));
        assertEquals(32, TraceStore.get("web.brave.mapper.responseLength"));
        String snapshot = TraceStore.getAll().toString();
        assertFalse(snapshot.contains("raw private"));
        assertFalse(snapshot.contains("secret"));
    }
}
