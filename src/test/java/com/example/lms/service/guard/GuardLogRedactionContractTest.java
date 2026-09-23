package com.example.lms.service.guard;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardLogRedactionContractTest {

    @Test
    void guardLogsDoNotWriteRawTokensOrEntities() throws Exception {
        String sanitizer = Files.readString(
                Path.of("main/java/com/example/lms/guard/UniversalGuardSanitizer.java"),
                StandardCharsets.UTF_8);
        String citationGate = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/CitationGate.java"),
                StandardCharsets.UTF_8);

        assertFalse(sanitizer.contains("badToken='{}'"));
        assertTrue(sanitizer.contains("badTokenHash={} badTokenLength={}"));
        assertTrue(sanitizer.contains("SafeRedactor.hashValue(badToken)"));

        assertFalse(citationGate.contains("Entity '{}' appears"));
        assertTrue(citationGate.contains("entityHash={} entityLength={}"));
        assertTrue(citationGate.contains("SafeRedactor.hashValue(ent)"));
        assertTrue(citationGate.contains("log.debug(\"[CitationGate] fail-soft stage={}\", \"gateChain.trace\")"));
    }
}
