package com.example.risk;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RealRiskModelProviderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidCoefficientTokenLeavesBreadcrumbAndKeepsValidWeights() throws Exception {
        RealRiskModelProvider provider = new RealRiskModelProvider();
        set(provider, "enabled", true);
        set(provider, "coefCsv", "bad,0.5");

        RiskModelProvider.Classifier classifier = provider.get();

        assertNotNull(classifier);
        assertEquals("coefficientParse", TraceStore.get("risk.model.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("risk.model.suppressed.errorType"));
        assertEquals(true, TraceStore.get("risk.model.suppressed.coefficientParse"));
        assertEquals("invalid_number", TraceStore.get("risk.model.suppressed.coefficientParse.errorType"));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
