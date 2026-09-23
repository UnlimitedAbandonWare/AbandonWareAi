package com.example.lms.guard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardPropertyBindingContractTest {

    @Test
    void legacyCitationGateAndPiiSanitizerUseSpringValueDefaultsInsteadOfSystemProperties() throws Exception {
        String citationGate = Files.readString(Path.of("main/java/com/example/lms/guard/CitationGate.java"));
        String piiSanitizer = Files.readString(Path.of("main/java/com/example/lms/guard/PiiSanitizer.java"));

        assertTrue(citationGate.contains("@Value(\"${guard.citation.min_count:2}\")"));
        assertTrue(citationGate.contains("@Value(\"${guard.citation.require_official:true}\")"));
        assertTrue(piiSanitizer.contains("@Value(\"${guard.pii.enabled:true}\")"));
        assertTrue(piiSanitizer.contains("@Value(\"${guard.pii.mode:redact}\")"));
        assertFalse(citationGate.contains("System.getProperty"));
        assertFalse(piiSanitizer.contains("System.getProperty"));
    }
}
