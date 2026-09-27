package com.example.lms.transform;

import com.example.lms.infra.resilience.NightmareBreaker;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryTransformerFailureLocaleTest {

    @Test
    void requiredModelMessagesRemainConfigurationFailuresUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals(
                    NightmareBreaker.FailureKind.CONFIG,
                    QueryTransformerFailureSupport.classifyLlmFailure(
                            new RuntimeException("MODEL IS REQUIRED")));
            assertEquals(
                    NightmareBreaker.FailureKind.CONFIG,
                    QueryTransformerFailureSupport.classifyLlmFailure(
                            new RuntimeException("MODEL PARAMETER IS REQUIRED")));
            assertEquals(
                    NightmareBreaker.FailureKind.UNKNOWN,
                    QueryTransformerFailureSupport.classifyLlmFailure(
                            new RuntimeException("UNRELATED FAILURE")));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
