package ai.abandonware.nova.orch.llm;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGuardSupportLocaleTest {

    @Test
    void officialOpenAiBaseUrlRecognitionIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertTrue(ModelGuardSupport.looksLikeOpenAiBaseUrl("HTTPS://API.OPENAI.COM/V1"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
