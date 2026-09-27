package com.example.lms.llm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiTokenParamErrorLocaleTest {
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void tokenErrorClassificationIsIndependentOfLocale(String languageTag) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            assertTrue(OpenAiTokenParamCompat.isUnsupportedMaxTokens(
                    new IllegalStateException("wrapper", new IllegalArgumentException("INVALID_REQUEST_ERROR: MAX_TOKENS"))));
            assertFalse(OpenAiTokenParamCompat.isUnsupportedMaxTokens(new IllegalArgumentException("network failure")));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void samplingErrorClassificationRetainsUnrelatedParameterControl(String languageTag) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            assertTrue(OpenAiTokenParamCompat.isUnsupportedTemperature(
                    new IllegalArgumentException("INVALID_REQUEST_ERROR: TEMPERATURE")));
            assertFalse(OpenAiTokenParamCompat.isUnsupportedTemperature(
                    new IllegalArgumentException("INVALID_REQUEST_ERROR: UNRELATED_PARAMETER")));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
