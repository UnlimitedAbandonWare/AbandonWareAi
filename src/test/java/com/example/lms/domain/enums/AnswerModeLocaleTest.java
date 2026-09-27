package com.example.lms.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerModeLocaleTest {

    @Test
    void supportedAnswerModeParsingIsIndependentOfDefaultLocale() {
        AnswerMode parsed;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            parsed = AnswerMode.fromString("creative");
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals(AnswerMode.CREATIVE, parsed);
    }
}
