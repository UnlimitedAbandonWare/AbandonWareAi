package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.contract.ValidationException;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerRedactionTest {

    @Test
    void validationDetailsAreRedactedBeforeResponseBody() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/web/GlobalExceptionHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("body.put(\"details\", ex.getErrors().toString());"));
        assertTrue(source.contains("SafeRedactor.safeMessage(String.valueOf(ex.getErrors()), 240)"));

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Map<String, Object> body = handler.handleValidation(new ValidationException(Set.of(
                "bad token " + com.example.lms.test.SecretFixtures.openAiKey() + " and path C:\\private\\agent\\payload.json"))).getBody();

        String rendered = String.valueOf(body);
        assertFalse(rendered.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertTrue(rendered.contains(SafeRedactor.hashValue(String.valueOf(Set.of(
                "bad token " + com.example.lms.test.SecretFixtures.openAiKey() + " and path C:\\private\\agent\\payload.json")))));
    }
}
