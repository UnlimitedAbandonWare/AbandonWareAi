package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PIISanitizerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void masksCommonPiiAndCredentialFragments() {
        PIISanitizer sanitizer = new PIISanitizer();
        String supabaseKey = "sb_secret_" + "piiservice01";

        String masked = sanitizer.mask("""
                contact user.name@example.com or 010-1234-5678
                rrn 901010-1234567 card 4111-1111-1111-1111
                Authorization: %s%s api_key=raw-api-value password=letmein
                Supabase key %s
                """.formatted("Bearer ", "raw-owner-token-12345", supabaseKey));

        assertFalse(masked.contains("user.name@example.com"));
        assertFalse(masked.contains("010-1234-5678"));
        assertFalse(masked.contains("901010-1234567"));
        assertFalse(masked.contains("4111-1111-1111-1111"));
        assertFalse(masked.contains("raw-owner-token-12345"));
        assertFalse(masked.contains("raw-api-value"));
        assertFalse(masked.contains("letmein"));
        assertFalse(masked.contains(supabaseKey));
        assertTrue(masked.contains("[redacted-email]"));
        assertTrue(masked.contains("[redacted-phone]"));
        assertTrue(masked.contains("[redacted-rrn]"));
        assertTrue(masked.contains("[redacted-card]"));
        assertTrue(masked.contains("[redacted-token]"));
        assertTrue(masked.contains("[redacted-secret]"));
        assertTrue(masked.contains("api_key=[redacted]"));
        assertTrue(masked.contains("password=[redacted]"));
    }

    @Test
    void preservesNullContract() {
        assertNull(new PIISanitizer().mask(null));
    }

    @Test
    void recordsCountOnlyTraceMetricsWithoutRawPii() {
        PIISanitizer sanitizer = new PIISanitizer();
        String input = "contact user.name@example.com api_key=raw-api-value";

        String masked = sanitizer.mask(input);

        assertEquals(Boolean.TRUE, TraceStore.get("piiSanitizer.service.applied"));
        assertEquals(2, TraceStore.get("piiSanitizer.service.changedCount"));
        assertEquals(input.length(), TraceStore.get("piiSanitizer.service.inputLength"));
        assertEquals(masked.length(), TraceStore.get("piiSanitizer.service.outputLength"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("user.name@example.com"), trace);
        assertFalse(trace.contains("raw-api-value"), trace);
    }

    @Test
    void piiSanitizerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/guard/PIISanitizer.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "PII sanitizer fail-soft metrics need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
