package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDbBrainSnapshotTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void malformedReturnedCountUsesStableReasonCodeWithoutRawValue() {
        TraceStore.clear();
        GraphDbBrainSnapshot snapshot = GraphDbBrainSnapshot.fromSummary(Map.of(
                "returnedCount", "private graphdb count"));

        assertEquals(0, snapshot.candidateCount());
        assertEquals("graphDb.snapshot.intValue", TraceStore.get("graphdb.snapshot.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("graphdb.snapshot.suppressed.errorType"));
        assertEquals("invalid_number",
                TraceStore.get("graphdb.snapshot.suppressed.graphDb.snapshot.intValue.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private graphdb count"));
    }

    @Test
    void distinctLongEvidenceIdsAreHashedBeforeProjectionClipping() {
        String commonPrefix = "A".repeat(160);
        String left = commonPrefix + "left";
        String right = commonPrefix + "right";
        String knownHash = "abcdef123456";

        GraphDbBrainSnapshot snapshot = GraphDbBrainSnapshot.fromSummary(Map.of(
                "communities", List.of(
                        community("community:" + left, left),
                        community("community:" + right, right),
                        community("community:" + knownHash, knownHash)),
                "multiHopEvidence", List.of(
                        multiHop(left),
                        multiHop(right),
                        multiHop(knownHash))));

        assertAll(
                () -> assertEquals(3, snapshot.communityIds().size()),
                () -> assertEquals(3, snapshot.chunkHashes().size()),
                () -> assertEquals(3, snapshot.textHashes().size()),
                () -> assertEquals(3, snapshot.pathHashes().size()),
                () -> assertEquals(3, snapshot.connectorHashes().size()),
                () -> assertTrue(snapshot.communityIds().contains("community:" + knownHash)),
                () -> assertTrue(snapshot.chunkHashes().contains(knownHash)),
                () -> assertTrue(snapshot.textHashes().contains(knownHash)),
                () -> assertTrue(snapshot.pathHashes().contains(knownHash)),
                () -> assertTrue(snapshot.connectorHashes().contains(knownHash)),
                () -> assertFalse(snapshot.toMap().toString().contains(left)),
                () -> assertFalse(snapshot.toMap().toString().contains(right)));
    }

    private static Map<String, Object> community(String communityId, String evidenceId) {
        return Map.of(
                "communityId", communityId,
                "chunkHashes", List.of(evidenceId),
                "textHashes", List.of(evidenceId));
    }

    private static Map<String, Object> multiHop(String evidenceId) {
        return Map.of(
                "pathHash", evidenceId,
                "connectorHash", evidenceId,
                "relationSource", "manual");
    }
}
