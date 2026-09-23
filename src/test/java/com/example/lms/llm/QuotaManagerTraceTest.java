package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QuotaManagerTraceTest {

    private String envName;

    @AfterEach
    void clearState() {
        if (envName != null) {
            System.clearProperty(envName);
        }
        TraceStore.clear();
    }

    @Test
    void invalidModelLimitFallsBackWithRedactedTraceBreadcrumb() {
        String model = "trace-test-model-" + Long.toUnsignedString(System.nanoTime());
        envName = model.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_LIMIT_RPM";
        System.setProperty(envName, "not-a-number-private-value");

        String selected = QuotaManager.getAvailableModel(model);

        assertEquals(model, selected);
        assertEquals(true, TraceStore.get("llm.quota.suppressed.limitParse"));
        assertEquals("invalid_number",
                TraceStore.get("llm.quota.suppressed.limitParse.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("not-a-number-private-value"));
    }
}
