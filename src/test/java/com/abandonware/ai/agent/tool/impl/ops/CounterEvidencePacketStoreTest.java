package com.abandonware.ai.agent.tool.impl.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CounterEvidencePacketStoreTest {

    @Test
    void packetIsSessionBoundOneShotExpiringAndContainsOnlyOpaqueEvidenceBindings() {
        AtomicLong now = new AtomicLong(1_000L);
        CounterEvidencePacketStore store = new CounterEvidencePacketStore(8, 500L, now::get);
        String packetRef = store.issue(
                "private-session-a",
                "hash:111111111111",
                "hash:222222222222",
                List.of("hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", "hash:cccccccccccc"),
                List.of(Map.of(
                        "evidenceId", "hash:dddddddddddd",
                        "independenceGroupHash", "hash:eeeeeeeeeeee",
                        "queryTraceRef", "hash:aaaaaaaaaaaa",
                        "querySlot", "AUTHORITATIVE_CONSTRAINT",
                        "observedAt", "2026-07-15T00:00:00Z",
                        "retrievalScore", 0.8d,
                        "relation", "CONFLICTS",
                        "crossValidationScore", 0.75d)));

        assertTrue(packetRef.matches("hash:[0-9a-f]{12}"), packetRef);
        assertTrue(store.consume(packetRef, "wrong-session").isEmpty());
        CounterEvidencePacketStore.Packet packet = store.consume(packetRef, "private-session-a").orElseThrow();
        assertTrue(store.consume(packetRef, "private-session-a").isEmpty());
        assertFalse(packet.toString().contains("private-session-a"), packet.toString());
        assertFalse(packet.toString().contains("private query"), packet.toString());
        assertFalse(packet.toString().contains("snippet"), packet.toString());
        assertTrue(packet.evidenceBindings().get(0).relationHint().equals("CONFLICTS"));
        assertTrue(packet.evidenceBindings().get(0).crossValidationScore() >= 0.75d);

        String expiringRef = store.issue(
                "session-b",
                "hash:111111111111",
                "hash:222222222222",
                List.of("hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", "hash:cccccccccccc"),
                List.of());
        now.addAndGet(501L);
        assertTrue(store.consume(expiringRef, "session-b").isEmpty());
    }

    @Test
    void refusesRawClaimOrQuestionAtTheStorageBoundary() {
        CounterEvidencePacketStore store = new CounterEvidencePacketStore();

        assertThrows(IllegalArgumentException.class, () -> store.issue(
                "session",
                "raw private claim",
                "hash:222222222222",
                List.of("hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", "hash:cccccccccccc"),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.issue(
                "session",
                "hash:111111111111",
                "raw private question",
                List.of("hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", "hash:cccccccccccc"),
                List.of()));
    }
}
