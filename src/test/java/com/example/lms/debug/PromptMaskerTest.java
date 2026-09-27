package com.example.lms.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptMaskerTest {

    @Test
    void masksKnownVendorSecretPrefixesAndKeyValuePairs() {
        String raw = String.join(" ",
                "sk-1234567890abcdef",
                "gsk_1234567890abcdef",
                "pcsk_1234567890abcdef",
                "tvly-1234567890abcdef",
                "KakaoAK abcdef1234567890",
                "naver.client-secret=abcdef1234567890",
                "x-subscription-token:abcdef1234567890");

        String masked = PromptMasker.mask(raw);

        assertFalse(masked.contains("sk-1234567890abcdef"));
        assertFalse(masked.contains("gsk_1234567890abcdef"));
        assertFalse(masked.contains("pcsk_1234567890abcdef"));
        assertFalse(masked.contains("tvly-1234567890abcdef"));
        assertFalse(masked.contains("abcdef1234567890"));
    }

    @Test
    void masksGenericSecretKeyValuePairs() {
        String raw = "llm failed token=test-secret-abcdefghijklmnop secret:abc12345 password=\"change-me-now\"";

        String masked = PromptMasker.mask(raw);

        assertTrue(masked.contains("token="));
        assertTrue(masked.contains("secret:"));
        assertTrue(masked.contains("password=\""));
        assertFalse(masked.contains("test-secret-abcdefghijklmnop"));
        assertFalse(masked.contains("abc12345"));
        assertFalse(masked.contains("change-me-now"));
    }

    @Test
    void masksSupabaseApiKeyPrefixes() {
        String secretKey = "sb_secret_" + "1234567890abcdef";
        String publishableKey = "sb_publishable_" + "1234567890abcdef";
        String raw = String.join(" ",
                secretKey,
                publishableKey);

        String masked = PromptMasker.mask(raw);

        assertFalse(masked.contains(secretKey));
        assertFalse(masked.contains(publishableKey));
    }

    @Test
    void masksHangulAdjacentSecretLabelsWithoutMatchingAsciiIdentifierSubstrings() {
        String secret = "fixture-secret-abcdefghijklmnop";
        String stars = "*".repeat(secret.length());

        assertEquals(
                "노출ownerToken=" + stars,
                PromptMasker.mask("노출ownerToken=" + secret));

        for (String asciiIdentifierPrefix : new String[]{"x", "9", "_"}) {
            String embedded = asciiIdentifierPrefix + "ownerToken=" + secret;
            assertEquals(embedded, PromptMasker.mask(embedded));
        }

        assertEquals(
                "앞 ownerToken=\"" + stars + "\" 뒤",
                PromptMasker.mask("앞 ownerToken=\"" + secret + "\" 뒤"));

        String mixed = "노출ownerToken=" + secret
                + " xownerToken=" + secret
                + " ownerToken='" + secret + "'";
        assertEquals(
                "노출ownerToken=" + stars
                        + " xownerToken=" + secret
                        + " ownerToken='" + stars + "'",
                PromptMasker.mask(mixed));
    }
}
