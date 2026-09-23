package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginViewContractTest {

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}");

    @Test
    void loginRouteReturnsLocalTemplateAndStatusCodesOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/web/PageController.java"), StandardCharsets.UTF_8);

        assertTrue(source.contains("@GetMapping(\"/login\")"));
        assertTrue(source.contains("model.addAttribute(\"loginStatus\", \"invalid_credentials\")"));
        assertTrue(source.contains("model.addAttribute(\"loginStatus\", \"signed_out\")"));
        assertTrue(source.contains("model.addAttribute(\"loginStatus\", \"sign_in_required\")"));
        assertTrue(source.contains("return \"login\";"));
        assertFalse(source.contains("model.addAttribute(\"loginStatus\", error)"));
    }

    @Test
    void loginTemplateProvidesCredentialFormWithoutOperationalDiagnostics() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/login.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("Operator sign-in"));
        assertTrue(html.contains("method=\"post\""));
        assertTrue(html.contains("action=\"/login\""));
        assertTrue(html.contains("name=\"username\""));
        assertTrue(html.contains("name=\"password\""));
        assertTrue(html.contains("type=\"password\""));
        assertTrue(html.contains("name=\"remember-me\""));
        assertTrue(html.contains("<!-- CSRF_INPUT -->"));
        assertTrue(html.contains("href=\"/chat-ui\""));
        assertFalse(html.contains("Trace memory"));
        assertFalse(html.contains("/api/diagnostics/"));
        assertFalse(html.contains("auth disabled"));
        assertFalse(html.matches("(?s).*\\sth:[A-Za-z-]+=\".*"));
        assertFalse(SECRET_PATTERN.matcher(html).find());
    }

    @Test
    void securityConfigUsesExplicitLoginPageWithoutChangingAdminProtection() throws Exception {
        String config = Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"), StandardCharsets.UTF_8);

        assertTrue(config.contains(".loginPage(\"/login\")"));
        assertTrue(config.contains(".loginProcessingUrl(\"/login\")"));
        assertTrue(config.contains(".failureUrl(\"/login?error\")"));
        assertTrue(config.contains(".logoutSuccessUrl(\"/login?logout\")"));
        assertTrue(config.contains(".key(effectiveRememberMeKey())"));
        assertFalse(config.contains(".formLogin(form -> form.disable())"));
        assertFalse(config.contains(".logout(logout -> logout.disable())"));
        assertFalse(config.contains(".rememberMe(rem -> rem.disable())"));
        assertTrue(config.contains(".requestMatchers(\"/admin\", \"/admin/**\").hasRole(\"ADMIN\")"));
        assertTrue(config.contains("\"/model-settings\""));
        assertTrue(config.contains("\"/model-settings/**\""));
    }
}
