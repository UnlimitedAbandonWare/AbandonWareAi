package com.nova.protocol.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HmacSignerTest {

    @Test
    void rejectsPlaceholderSecrets() {
        Map<String, String> claims = claims(60);

        assertThrows(IllegalArgumentException.class, () -> HmacSigner.sign(claims, "change-me"));

        String token = HmacSigner.sign(claims, "real-rulebreak-secret");
        assertThrows(IllegalArgumentException.class,
                () -> HmacSigner.verifyAndDecode(token, "change-me", 60));
    }

    @Test
    void verifiesConfiguredSecretWithinTtl() {
        Map<String, String> claims = claims(60);
        String token = HmacSigner.sign(claims, "real-rulebreak-secret");

        Map<String, String> decoded = HmacSigner.verifyAndDecode(token, "real-rulebreak-secret", 120);

        assertEquals("SAFE_EXPLORE", decoded.get("policy"));
    }

    @Test
    void roundTripsEscapedClaimValues() {
        long now = Instant.now().getEpochSecond();
        Map<String, String> claims = Map.of(
                "policy", "SAFE&EXPLORE=TRUE",
                "subject", "agent+desktop@example.test",
                "exp", String.valueOf(now + 60));
        String token = HmacSigner.sign(claims, "real-rulebreak-secret");

        Map<String, String> decoded = HmacSigner.verifyAndDecode(token, "real-rulebreak-secret", 120);

        assertEquals("SAFE&EXPLORE=TRUE", decoded.get("policy"));
        assertEquals("agent+desktop@example.test", decoded.get("subject"));
    }

    @Test
    void rejectsTokensWithExpiryBeyondAllowedTtl() {
        Map<String, String> claims = claims(3600);
        String token = HmacSigner.sign(claims, "real-rulebreak-secret");

        assertThrows(IllegalArgumentException.class,
                () -> HmacSigner.verifyAndDecode(token, "real-rulebreak-secret", 60));
    }

    private static Map<String, String> claims(long expiresInSeconds) {
        long now = Instant.now().getEpochSecond();
        return Map.of(
                "policy", "SAFE_EXPLORE",
                "iat", String.valueOf(now),
                "exp", String.valueOf(now + expiresInSeconds));
    }
}
