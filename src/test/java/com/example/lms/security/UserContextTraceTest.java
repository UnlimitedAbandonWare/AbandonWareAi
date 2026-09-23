package com.example.lms.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTraceTest {

    @Test
    void currentUserFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/security/UserContext.java"));

        assertTrue(source.contains("traceSuppressed(\"userContext.currentUser\", ignore);"));
        assertTrue(source.contains("userContext.suppressed."));
    }
}
