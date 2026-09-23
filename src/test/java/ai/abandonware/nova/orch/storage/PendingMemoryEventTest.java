package ai.abandonware.nova.orch.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PendingMemoryEventTest {

    @Test
    void constructorNormalizesMissingTimestampAndNegativeSize() {
        PendingMemoryEvent event = new PendingMemoryEvent(
                "session",
                "context",
                "hash:query",
                "snippet",
                null,
                -12L,
                "memory_disabled");

        assertNotNull(event.occurredAt());
        assertEquals(Instant.EPOCH, event.occurredAt());
        assertEquals(Instant.EPOCH, event.timestamp());
        assertEquals(0L, event.sizeBytes());
    }
}
