package com.abandonware.ai.agent.consent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentCardRendererRedactionTest {

    @Test
    void fallbackScopeCsvKeepsScopeNamesButHashesUnsafeText() {
        String rendered = ReflectionTestUtils.invokeMethod(
                ConsentCardRenderer.class,
                "safeScopesCsv",
                (Object) new String[]{"WEB_GET", "owner token fake-sensitive-token"});

        assertTrue(rendered.contains("WEB_GET"), rendered);
        assertTrue(rendered.contains("hash:"), rendered);
        assertFalse(rendered.contains("owner token"), rendered);
        assertFalse(rendered.contains("fake-sensitive-token"), rendered);
    }

    @Test
    void missingTemplateFallbackLeavesTraceAndAvoidsRawExceptionMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/consent/ConsentCardRenderer.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"template.read\", e);"));
        assertTrue(source.contains("\" + safeScopesCsv(scopes) + \""));
        assertTrue(source.contains("agent.consent.card.suppressed.stage"));
        assertFalse(source.contains("e.getMessage()"));
    }
}
