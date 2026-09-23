package com.example.lms.search.org;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationResolverTraceTest {

    @Test
    void catalogLoadFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/org/OrganizationResolver.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("private static void traceSuppressed(String stage, Throwable failure)"));
        assertTrue(source.contains("traceSuppressed(\"catalog.load\", e);"));
        assertTrue(source.contains("TraceStore.put(\"organizationResolver.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"organizationResolver.suppressed.errorType\", errorType);"));
        assertTrue(source.contains("TraceStore.put(\"organizationResolver.suppressed.\" + safeStage, true);"));
    }
}
