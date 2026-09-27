package com.example.lms.infra.resilience;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NightmareBreakerLocaleTest {

    @Test
    void uppercaseConfigurationFailureRemainsClassifiedUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertAll(
                    () -> assertEquals(
                            NightmareBreaker.FailureKind.CONFIG,
                            NightmareBreaker.classify(new RuntimeException("MODEL IS REQUIRED"))),
                    () -> assertEquals(
                            NightmareBreaker.FailureKind.CONFIG,
                            NightmareBreaker.classify(new RuntimeException("model is required"))),
                    () -> assertEquals(
                            NightmareBreaker.FailureKind.UNKNOWN,
                            NightmareBreaker.classify(new RuntimeException("completely unrelated failure"))));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
