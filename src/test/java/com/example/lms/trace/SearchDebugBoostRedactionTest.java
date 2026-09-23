package com.example.lms.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchDebugBoostRedactionTest {

    @Test
    void reasonDoesNotExposeFreeFormBoostReason() {
        SearchDebugBoost boost = new SearchDebugBoost(true, 2, "query-transformer:", "TIMEOUT", "", "");
        String privateReason = "private student debug query timeout";

        assertTrue(boost.boost(privateReason));

        String reason = boost.reason();
        assertFalse(reason.contains(privateReason), reason);
        assertFalse(reason.contains("private student"), reason);
        assertFalse(reason.contains("debug query"), reason);
        assertTrue(reason.startsWith("hash:"), reason);
    }
}
