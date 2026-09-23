package com.example.lms.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatSessionScopeTest {

    @AfterEach
    void clear() {
        ChatSessionScope.clear();
    }

    @Test
    void enterNullClearsCurrentSession() {
        ChatSessionScope.enter(42L);

        ChatSessionScope.enter(null);

        assertNull(ChatSessionScope.current());
    }

    @Test
    void leaveClearsCurrentSession() {
        ChatSessionScope.enter(7L);

        ChatSessionScope.leave();

        assertNull(ChatSessionScope.current());
    }

    @Test
    void enterStoresCurrentSession() {
        ChatSessionScope.enter(99L);

        assertEquals(99L, ChatSessionScope.current());
    }
}
