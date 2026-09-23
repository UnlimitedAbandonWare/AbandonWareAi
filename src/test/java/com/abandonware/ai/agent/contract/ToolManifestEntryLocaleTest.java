package com.abandonware.ai.agent.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolManifestEntryLocaleTest {

    @Test
    void recognizesUppercaseWriteRiskUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertTrue(entry("WRITE_CONTROLLED").sideEffectRisk());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void keepsOrdinaryReadOnlyRiskNonSideEffecting() {
        assertFalse(entry("none").sideEffectRisk());
    }

    private static ToolManifestEntry entry(String risk) {
        return new ToolManifestEntry(
                "ops.test",
                true,
                "test entry",
                risk,
                List.of("ops:read"),
                true,
                false,
                4096,
                false,
                "/internal/test",
                "",
                Map.of()
        );
    }
}
