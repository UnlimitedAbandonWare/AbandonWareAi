package com.example.lms.service.rag.filter;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;

class GenericDocClassifierLocaleTest {

    private final GenericDocClassifier classifier = new GenericDocClassifier();

    @Test
    void educationSnippetExemptionIsIndependentOfDefaultLocale() {
        assertUnderTurkishLocale(() -> assertFalse(
                classifier.isGenericSnippet("모든 캐릭터 총정리", "education")));
    }

    @Test
    void educationTextExemptionIsIndependentOfDefaultLocale() {
        assertUnderTurkishLocale(() -> assertFalse(
                classifier.isGenericText("모든 캐릭터 총정리", "education")));
    }

    private static void assertUnderTurkishLocale(Runnable assertion) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertion.run();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
