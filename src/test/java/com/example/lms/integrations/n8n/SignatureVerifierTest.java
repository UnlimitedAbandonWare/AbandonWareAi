package com.example.lms.integrations.n8n;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureVerifierTest {

    @Test
    void missingSecretRejectsEmptyKeySignatures() throws Exception {
        byte[] body = "{\"event\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
        SignatureVerifier verifier = new SignatureVerifier("");

        assertFalse(verifier.verify(body, "sha256=00"));
    }

    @Test
    void placeholderSecretRejectsMatchingPlaceholderSignatures() throws Exception {
        byte[] body = "{\"event\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
        SignatureVerifier verifier = new SignatureVerifier("sk-local");

        assertFalse(verifier.verify(body, signature(body, "sk-local")));
    }

    @Test
    void configuredSecretAcceptsMatchingSignature() throws Exception {
        byte[] body = "{\"event\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
        SignatureVerifier verifier = new SignatureVerifier("real-n8n-webhook-secret");

        assertTrue(verifier.verify(body, signature(body, "real-n8n-webhook-secret")));
    }

    private static String signature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder out = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(body)) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
