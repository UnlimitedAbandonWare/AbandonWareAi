package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiOfficialEndpointLocaleTest {

    @Test
    void officialEndpointTokenParameterIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals(
                    "max_completion_tokens",
                    OpenAiTokenParamCompat.tokenParamKey("gpt-5", "HTTPS://API.OPENAI.COM/V1"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
