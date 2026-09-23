package com.example.lms.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatOpenSecurityConfigContractTest {

    @Test
    void chatOpenChainIsServletOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/security/ChatOpenSecurityConfig.java"));

        assertTrue(source.contains("import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;"));
        assertMethodPreambleContains(source,
                "SecurityFilterChain chatOpenChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
    }

    private static void assertMethodPreambleContains(String source, String methodSignature, String expected) {
        int method = source.indexOf(methodSignature);
        assertTrue(method > 0, methodSignature);
        String preamble = source.substring(Math.max(0, method - 240), method);
        assertTrue(preamble.contains(expected), expected);
    }
}
