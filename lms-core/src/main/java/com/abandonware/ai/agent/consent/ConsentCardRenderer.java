package com.abandonware.ai.agent.consent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.consent.ConsentCardRenderer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.consent.ConsentCardRenderer
role: config
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