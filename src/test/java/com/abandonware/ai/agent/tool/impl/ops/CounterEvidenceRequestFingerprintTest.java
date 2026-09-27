package com.abandonware.ai.agent.tool.impl.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterEvidenceRequestFingerprintTest {

    @Test
    void preservesCanonicalV1Fingerprint() {
        String fingerprint = CounterEvidenceRequestFingerprint.canonicalRequestHash(
                "claim",
                "question",
                List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                Map.of("maxDocuments", 3, "maxMillis", 5_000));

        assertEquals("hash:d7689a97c5ea", fingerprint);
    }
}
