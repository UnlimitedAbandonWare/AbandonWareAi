package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomSecurityConfigContractTest {

    @Test
    void forceHttpsAdminChainPrecedesDefaultCatchAllChain() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/CustomSecurityConfig.java"));

        assertFalse(source.contains("@Order(3)"));
        assertTrue(source.contains("@Order(Ordered.HIGHEST_PRECEDENCE + 20)"));
        assertTrue(source.contains("http.securityMatcher(\"/admin/**\", \"/api/admin/**\", \"/dashboard/**\", \"/model-settings/**\", \"/logout\")"));
        assertTrue(source.contains(".requiresChannel(channel -> channel.anyRequest().requiresSecure())"));
    }

    @Test
    void forceHttpsAdminChainIsServletOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/CustomSecurityConfig.java"));

        assertTrue(source.contains("import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;"));
        assertMethodPreambleContains(source,
                "SecurityFilterChain adminSecurity",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
    }

    @Test
    void forceHttpsAdminChainPreservesRememberMeAndLogoutBoundary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/CustomSecurityConfig.java"));

        assertFalse(source.contains(".formLogin(form -> form.disable())"));
        assertFalse(source.contains(".logout(logout -> logout.disable())"));
        assertFalse(source.contains(".rememberMe(rem -> rem.disable())"));
        assertTrue(source.contains(".key(effectiveRememberMeKey())"));
        assertTrue(source.contains("logoutRequestMatcher(new AntPathRequestMatcher(\"/logout\", \"POST\"))"));
        assertTrue(source.contains("logoutSuccessUrl(\"/login?logout\")"));
        assertTrue(source.contains("adminTokenGuardInterceptor.revokePresentedSession(request, response)"));
    }

    private static void assertMethodPreambleContains(String source, String methodSignature, String expected) {
        int method = source.indexOf(methodSignature);
        assertTrue(method > 0, methodSignature);
        String preamble = source.substring(Math.max(0, method - 240), method);
        assertTrue(preamble.contains(expected), expected);
    }
}
