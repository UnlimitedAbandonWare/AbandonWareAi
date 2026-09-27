package com.abandonware.ai.agent.tool.impl.ops;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorProbeAuthorityStoreTest {

    @Test
    void authorityIsOpaqueSessionBoundOneShotExpiringAndBudgetBounded() {
        AtomicLong now = new AtomicLong(1_000L);
        OperatorProbeAuthorityStore store = new OperatorProbeAuthorityStore(8, 500L, now::get);

        String authorityRef = store.issue(
                "private-session-a",
                "hash:111111111111",
                "hash:222222222222",
                "hash:333333333333",
                OperatorProbeAuthorityStore.Mode.OBSERVE_ONLY,
                250);

        assertTrue(authorityRef.matches("hash:[0-9a-f]{12}"), authorityRef);
        assertTrue(store.consume(authorityRef, "wrong-session").isEmpty());
        OperatorProbeAuthorityStore.Authority authority = store.consume(
                authorityRef, "private-session-a").orElseThrow();
        assertEquals(250, authority.maxMillis());
        assertEquals(OperatorProbeAuthorityStore.Mode.OBSERVE_ONLY, authority.mode());
        assertEquals("hash:333333333333", authority.probeRequestHash());
        assertTrue(store.consume(authorityRef, "private-session-a").isEmpty());
        assertFalse(authority.toString().contains("private-session-a"), authority.toString());

        String expiringRef = store.issue(
                "session-b",
                "hash:111111111111",
                "hash:222222222222",
                "hash:333333333333",
                OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE,
                500);
        now.addAndGet(501L);
        assertTrue(store.consume(expiringRef, "session-b").isEmpty());
    }

    @Test
    void handoffTtlIsNotConsumedAsExecutionBudget() {
        AtomicLong now = new AtomicLong(1_000L);
        OperatorProbeAuthorityStore store = new OperatorProbeAuthorityStore(8, 500L, now::get);
        String authorityRef = store.issue(
                "session",
                "hash:111111111111",
                "hash:222222222222",
                "hash:333333333333",
                OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE,
                1);

        now.addAndGet(2L);
        assertTrue(store.peek(authorityRef, "session").isPresent());
    }
}
