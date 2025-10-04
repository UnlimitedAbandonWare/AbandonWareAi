package com.example.lms.integrations.n8n;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Simple HMAC based signature verifier for n8n webhooks.  When enabled
 * incoming webhook requests must include an {@code X-N8N-Signature} header
 * containing the Base64 encoded HMAC-SHA256 digest of the request body.
 * The secret is read from {@link N8nProps#getWebhookSecret()}.  When no
 * secret is configured or the integration is disabled this verifier
 * always returns {@code true}.
 */
@Component
@RequiredArgsConstructor
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    private final N8nProps props;
    private Mac mac;

    /**
     * Initialise the HMAC instance once the properties have been loaded.
     * When the secret is blank no MAC is created and verification
     * short‑circuits to {@code true}.
     */
    @PostConstruct
    void init() {
        try {
            if (props.getWebhookSecret() != null && !props.getWebhookSecret().isBlank()) {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(props.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            }
        } catch (Exception e) {
            log.warn("Failed to initialise HMAC for n8n signature verification: {}", e.toString());
            mac = null;
        }
    }

    /**
     * @return {@code true} when verification is enabled (secret present and integration enabled)
     */
    public boolean isEnabled() {
        return props.isEnabled() && props.getWebhookSecret() != null && !props.getWebhookSecret().isBlank();
    }

    /**
     * Verify a request signature.  When disabled or no secret is
     * configured this method returns {@code true}.  When the provided
     * signature is missing or cannot be decoded it returns {@code false}.
     * Any exceptions during verification result in a {@code false}
     * return value with a logged warning.
     *
     * @param signature base64 encoded HMAC signature from the header
     * @param payload   raw request body (may be null)
     * @return {@code true} when the signature matches, {@code false} otherwise
     */
    public boolean verify(String signature, String payload) {
        if (!isEnabled()) {
            return true;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            byte[] given = Base64.getDecoder().decode(signature.trim());
            byte[] body = payload != null ? payload.getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] expected;
            synchronized (this) {
                expected = mac.doFinal(body);
            }
            if (given.length != expected.length) return false;
            int diff = 0;
            for (int i = 0; i < given.length; i++) {
                diff |= given[i] ^ expected[i];
            }
            return diff == 0;
        } catch (Exception e) {
            log.warn("n8n signature verification error: {}", e.toString());
            return false;
        }
    }
}