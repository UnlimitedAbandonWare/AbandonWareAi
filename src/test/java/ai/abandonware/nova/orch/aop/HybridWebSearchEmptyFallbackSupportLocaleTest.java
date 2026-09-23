package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridWebSearchEmptyFallbackSupportLocaleTest {

    @Test
    void uppercaseLowTrustHostsRemainClassifiedUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertAll(
                    () -> assertTrue(HybridWebSearchEmptyFallbackSupport.isLowTrustHost("TISTORY.COM")),
                    () -> assertTrue(HybridWebSearchEmptyFallbackSupport.isLowTrustHost("INVEN.CO.KR")),
                    () -> assertTrue(HybridWebSearchEmptyFallbackSupport.isLowTrustHost("tistory.com")),
                    () -> assertFalse(HybridWebSearchEmptyFallbackSupport.isLowTrustHost("EXAMPLE.COM")),
                    () -> assertFalse(HybridWebSearchEmptyFallbackSupport.isLowTrustHost(null)),
                    () -> assertFalse(HybridWebSearchEmptyFallbackSupport.isLowTrustHost("  ")));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
