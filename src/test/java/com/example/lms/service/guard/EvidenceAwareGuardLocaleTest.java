package com.example.lms.service.guard;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EvidenceAwareGuardLocaleTest {

    @Test
    void subcultureDomainClassificationIsIndependentOfDefaultLocale() {
        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertAll(
                    () -> assertTrue(isSubcultureDomain(guard, "GALL.DCINSIDE.COM")),
                    () -> assertTrue(isSubcultureDomain(guard, "GALL.INVEN.CO.KR")),
                    () -> assertTrue(isSubcultureDomain(guard, "gall.dcinside.com")),
                    () -> assertFalse(isSubcultureDomain(guard, null)),
                    () -> assertFalse(isSubcultureDomain(guard, "EXAMPLE.COM")),
                    () -> assertFalse(isSubcultureDomain(guard, "NAMU.WIKI")));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static boolean isSubcultureDomain(EvidenceAwareGuard guard, String url) {
        Boolean result = ReflectionTestUtils.invokeMethod(
                guard,
                "isSubcultureDomain",
                (Object) url);
        return Boolean.TRUE.equals(result);
    }
}
