package com.abandonware.ai.agent.consent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;




/**
 * Utility for rendering consent request cards.  Given a list of scopes and a
 * time to live, this class will load the basic card template from the
 * classpath and substitute a number of placeholders.  The resulting JSON
 * payload can be returned directly to a Kakao channel as a v2.0 basicCard.
 */
public class ConsentCardRenderer {

    private static final String TEMPLATE_PATH = "templates/kakao_consent_card.basic.json";
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
            // If the template is missing fall back to a simple JSON string.
            return "{\"error\":\"Consent template missing\",\"scopes\":\"" + String.join(",", scopes) + "\"}";
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
}