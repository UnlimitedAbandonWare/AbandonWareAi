package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAuthBreadcrumbContractTest {

    @Test
    void ownerEnrollmentAndModelFailuresUseRedactedBreadcrumbs() throws Exception {
        String clientOwner = source("ClientOwnerKeyResolver.java");
        String bootstrap = source("OwnerKeyBootstrapFilter.java");
        String owner = source("OwnerKeyResolver.java");
        String page = source("PageController.java");

        assertTrue(clientOwner.contains("Client owner gid cookie rejected"));
        assertTrue(clientOwner.contains("Client owner fallback hash unavailable"));
        assertTrue(bootstrap.contains("Rejected malformed ownerKey cookie"));
        assertTrue(owner.contains("Owner guest hash fallback used"));
        assertTrue(page.contains("Model settings validation rejected modelHash={} modelLength={}"));
        assertTrue(page.contains("SafeRedactor.hashValue(modelId)"));
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of("main/java/com/example/lms/web", fileName), StandardCharsets.UTF_8);
    }
}
