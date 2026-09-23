package com.example.lms.risk.rdi;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RealRiskModelProviderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidCoefficientUsesStableReasonCodeWithoutRawValue() {
        RealRiskModelProvider provider = new RealRiskModelProvider();
        ReflectionTestUtils.setField(provider, "enabled", true);
        ReflectionTestUtils.setField(provider, "bias", 0.0d);
        ReflectionTestUtils.setField(provider, "coefCsv", "0.2,ownerToken-not-a-number,0.4");

        RiskModelProvider.Classifier classifier = provider.get();

        assertNotNull(classifier);
        assertEquals("invalid_number", TraceStore.get("risk.model.suppressed.coefficientParse.errorType"));
        assertEquals("coefficientParse", TraceStore.get("risk.model.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("risk.model.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-not-a-number"));
        assertFalse(trace.contains("NumberFormatException"));
    }
}
