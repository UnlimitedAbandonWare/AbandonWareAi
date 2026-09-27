package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PipelineSnapshotCountsFocusedTest {
    @Test
    void observedEmptyWebResultsRemainZeroWhileUnobservedVectorRemainsNull() {
        var snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("finalWebTopK", List.of()), null, null, null);

        assertNotNull(snapshot);
        assertEquals(0, snapshot.webCount());
        assertNull(snapshot.vectorCount());
        assertEquals(0, snapshot.finalContextCount());
    }

    @Test
    void absentOrMalformedEvidenceDoesNotInventZeroResults() {
        assertNull(ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of(), null, null, null));
        var snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("plan.id", "safe.v1", "finalWebTopK", "unavailable"), null, null, null);

        assertNull(snapshot.webCount());
        assertNull(snapshot.vectorCount());
        assertNull(snapshot.finalContextCount());
    }

    @Test
    void nonemptyAndExplicitCountsKeepTheirExistingPrecedence() {
        var snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("finalWebTopK", List.of("public-a", "public-b"),
                        "finalVectorTopK", Map.of(), "webCount", 0), null, null, null);

        assertEquals(0, snapshot.webCount());
        assertEquals(0, snapshot.vectorCount());
        assertEquals(0, snapshot.finalContextCount());

        var nonempty = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("finalWebTopK", List.of("public-a", "public-b")), null, null, null);
        assertEquals(2, nonempty.webCount());
        assertEquals(2, nonempty.finalContextCount());
    }
}
