package com.example.lms.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebProviderTraceReasonsTest {

    @Test
    void canonicalizesCredentialAndProviderStateReasons() {
        assertEquals("missing-key", WebProviderTraceReasons.disabledReason("missing_brave_api_key"));
        assertEquals("missing-key", WebProviderTraceReasons.disabledReason("missing_naver_client_credentials"));
        assertEquals("blank-or-dummy-key", WebProviderTraceReasons.disabledReason("invalid_naver_keys"));
        assertEquals("blank-or-dummy-key", WebProviderTraceReasons.disabledReason("dummy"));
        assertEquals("blank-or-dummy-key", WebProviderTraceReasons.disabledReason("${BRAVE_API_KEY}"));
        assertEquals("provider-disabled", WebProviderTraceReasons.disabledReason("disabled_by_config"));
        assertEquals("rate-limit", WebProviderTraceReasons.disabledReason("quota_exhausted"));
    }

    @Test
    void freeTextReasonsAreHashedInsteadOfMirrored() {
        String raw = "private reason client_secret=<test-secret> raw query";

        String canonical = WebProviderTraceReasons.disabledReason(raw);

        assertTrue(canonical.startsWith("hash:"), canonical);
        assertFalse(canonical.contains("client_secret"));
        assertFalse(canonical.contains("raw query"));
    }
}
