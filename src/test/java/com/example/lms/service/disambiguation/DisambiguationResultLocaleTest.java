package com.example.lms.service.disambiguation;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DisambiguationResultLocaleTest {

    @Test
    void highConfidenceParsingIsIndependentOfDefaultLocale() {
        DisambiguationResult result = new DisambiguationResult();
        result.setConfidence("HIGH");

        boolean confident;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            confident = result.isConfident();
        } finally {
            Locale.setDefault(previous);
        }

        assertTrue(confident);
    }
}
