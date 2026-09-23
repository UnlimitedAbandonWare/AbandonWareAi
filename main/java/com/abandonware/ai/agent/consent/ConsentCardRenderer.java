package com.abandonware.ai.agent.consent;

import com.example.lms.search.TraceStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;




/**
 * Utility for rendering consent request cards.  Given a list of scopes and a
 * time to live, this class will load the basic card template from the
 * classpath and substitute a number of placeholders.  The resulting JSON
 * payload can be returned directly to a Channel channel as a v2.0 basicCard.
 */
public class ConsentCardRenderer {

    private static final String TEMPLATE_PATH = "templates/channel_consent_card.basic.json";
    private static final String DEFAULT_GRANT_BLOCK_ID = "GRANT_CONSENT";
    private static final String DEFAULT_DENY_BLOCK_ID = "DENY_CONSENT";

    /**
     * Renders the basic consent card by substituting template variables.  If
     * the template cannot be read this method returns a minimal JSON object
     * describing the missing scopes.
     */
    public String renderBasic(String sessionId, String roomId, String[] scopes, long ttlSeconds) {
        String template;
        try {
            ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
            template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            traceSuppressed("template.read", e);
            // If the template is missing fall back to a simple JSON string.
            return "{\"error\":\"Consent template missing\",\"scopes\":\"" + safeScopesCsv(scopes) + "\"}";
        }
        String scopesCsv = String.join(",", scopes);
        String scopesArray = Arrays.toString(scopes);
        String minutes = String.valueOf(ttlSeconds / 60);
        return template
                .replace("${sessionId}", sessionId)
                .replace("${roomId}", roomId != null ? roomId : "")
                .replace("${scopes_csv}", scopesCsv)
                .replace("${scopes_array}", scopesArray)
                .replace("${ttl_seconds}", String.valueOf(ttlSeconds))
                .replace("${ttl_minutes}", minutes)
                .replace("${CONSENT_GRANT_BLOCK_ID}", DEFAULT_GRANT_BLOCK_ID)
                .replace("${CONSENT_DENY_BLOCK_ID}", DEFAULT_DENY_BLOCK_ID);
    }

    private static String safeScopesCsv(String[] scopes) {
        if (scopes == null || scopes.length == 0) {
            return "";
        }
        return Arrays.stream(scopes)
                .map(ConsentCardRenderer::safeScopeLabel)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(","));
    }

    private static String safeScopeLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= 80 && trimmed.matches("[A-Za-z0-9_.:-]+")) {
            return trimmed;
        }
        return "hash:" + org.apache.commons.codec.digest.DigestUtils.sha256Hex(trimmed).substring(0, 12);
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.put("agent.consent.card.suppressed", true);
        TraceStore.put("agent.consent.card.suppressed.stage", stage);
        TraceStore.put("agent.consent.card.suppressed.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
    }
}
