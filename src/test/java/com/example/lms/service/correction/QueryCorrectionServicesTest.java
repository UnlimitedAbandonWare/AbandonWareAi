package com.example.lms.service.correction;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryCorrectionServicesTest {

    @Test
    void defaultCorrectionNormalizesMinusSignWithoutRegexFailure() {
        DefaultQueryCorrectionService service = new DefaultQueryCorrectionService();

        assertEquals("alpha-beta", service.correct("alpha−−beta"));
    }

    @Test
    void vectorAliasCorrectionNormalizesMinusSignWithoutRegexFailure() {
        VectorAliasCorrector corrector = new VectorAliasCorrector(0.62, 3);

        assertEquals(Optional.of("alpha-beta"), corrector.correct("alpha−−beta"));
    }

    @Test
    void vectorAliasCorrectionMatchesBuiltInAliasIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            VectorAliasCorrector corrector = new VectorAliasCorrector(0.62, 3);

            assertEquals(Optional.of("Windows 11"), corrector.correct("WIN11"));
            assertEquals(Optional.of("Windows 11"), corrector.correct("win11"));
            assertEquals(Optional.empty(), corrector.correct("unknown-alias"));

            Locale.setDefault(Locale.US);
            assertEquals(Optional.of("Windows 11"), corrector.correct("WIN11"));
            assertEquals(Optional.of("Windows 11"), corrector.correct("win11"));
            assertEquals(Optional.empty(), corrector.correct("unknown-alias"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
