package com.example.lms.domain;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessageLocaleTest {

    @Test
    void roleNormalizationIsIndependentOfDefaultLocale() {
        ChatMessage message;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            message = new ChatMessage(null, "ASSISTANT", "answer");
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals("assistant", message.getRole());
    }
}
