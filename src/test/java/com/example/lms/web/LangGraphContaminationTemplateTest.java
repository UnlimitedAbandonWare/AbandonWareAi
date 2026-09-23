package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangGraphContaminationTemplateTest {

    @Test
    void templateUsesLocalAssetsAndCsrfMetadata() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/langgraph-contamination.html"));

        assertFalse(html.contains("https://cdn."));
        assertFalse(html.contains("https://unpkg."));
        assertFalse(html.contains("https://cdn.jsdelivr."));
        assertTrue(html.contains("name=\"_csrf\""));
        assertTrue(html.contains("name=\"_csrf_header\""));
        assertTrue(html.contains("/vendor/vue.global.prod.js"));
        assertTrue(Files.exists(Path.of("main/resources/static/vendor/vue.global.prod.js")));
    }
}
