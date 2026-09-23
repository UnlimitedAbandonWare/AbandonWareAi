package com.example.lms.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryHygieneFilterLocaleTest {

    @Test
    void sanitizeTreatsCaseVariantsAsDuplicatesUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            List<String> sanitized = QueryHygieneFilter.sanitize(
                    List.of("IBM earnings", "ibm earnings"), 4, 0.80d);

            assertEquals(List.of("IBM earnings"), sanitized);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void sanitizeAnchoredRecognizesExistingPrimaryUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            List<String> sanitized = QueryHygieneFilter.sanitizeAnchored(
                    List.of("IBM earnings"), 4, 0.80d, "ibm", null);

            assertEquals(List.of("IBM earnings"), sanitized);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
