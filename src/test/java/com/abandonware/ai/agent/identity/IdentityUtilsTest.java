package com.abandonware.ai.agent.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdentityUtilsTest {
    @Test
    void guestIdentityRequiresEstablishedSessionId() {
        assertNull(IdentityUtils.identityOf(null, null));
        assertNull(IdentityUtils.identityOf("", " "));
    }

    @Test
    void userIdentityUsesTrimmedUserIdBeforeSessionId() {
        assertEquals("user:alice", IdentityUtils.identityOf(" alice ", "session-1"));
    }

    @Test
    void guestIdentityUsesSessionIdWhenUserIdMissing() {
        assertEquals("guest:session-1", IdentityUtils.identityOf(null, "session-1"));
    }
}
