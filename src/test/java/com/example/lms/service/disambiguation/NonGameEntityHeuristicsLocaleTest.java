package com.example.lms.service.disambiguation;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonGameEntityHeuristicsLocaleTest {

    @Test
    void suspiciousPairDetectionIsIndependentOfDefaultLocale() {
        assertTrue(NonGameEntityHeuristics.containsSuspiciousPair("GENSHIN ESCOFFIER"));

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertTrue(NonGameEntityHeuristics.containsSuspiciousPair("GENSHIN ESCOFFIER"));
            assertFalse(NonGameEntityHeuristics.containsSuspiciousPair("GENSHIN MONDSTADT"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
