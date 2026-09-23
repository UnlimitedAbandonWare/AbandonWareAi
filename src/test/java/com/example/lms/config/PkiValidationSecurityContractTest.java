package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkiValidationSecurityContractTest {

    @Test
    void pkiValidationGetStaysPublicButMultipartPostIsNotPublicWrite() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/PkiValidationSecurityConfig.java"));

        assertTrue(source.contains(".requestMatchers(HttpMethod.GET, \"/.well-known/pki-validation/**\").permitAll()"));
        assertFalse(source.contains(".requestMatchers(HttpMethod.POST, \"/.well-known/pki-validation\").permitAll()"));
        assertTrue(source.contains(".requestMatchers(HttpMethod.POST, \"/.well-known/pki-validation\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains(".anyRequest().denyAll()"));

        String csrfBlock = source.substring(source.indexOf(".csrf(csrf -> csrf"));
        assertFalse(csrfBlock.contains("new AntPathRequestMatcher(\"/.well-known/pki-validation\", \"POST\")"));
        assertTrue(csrfBlock.contains("new AntPathRequestMatcher(\"/.well-known/pki-validation/**\", \"GET\")"));
    }

    @Test
    void pkiValidationChainIsServletOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/PkiValidationSecurityConfig.java"));

        assertTrue(source.contains("import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;"));
        assertMethodPreambleContains(source,
                "public SecurityFilterChain pkiValidationChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
    }

    private static void assertMethodPreambleContains(String source, String methodSignature, String expected) {
        int method = source.indexOf(methodSignature);
        assertTrue(method > 0, methodSignature);
        String preamble = source.substring(Math.max(0, method - 240), method);
        assertTrue(preamble.contains(expected), expected);
    }
}
