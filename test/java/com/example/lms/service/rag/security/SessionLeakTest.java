package com.example.lms.service.rag.security;

import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.LangChainRAGService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that VectorStoreService segregates identical snippets by session id.
 *
 * <p>Prior to hardening, the deduplication key ignored the session id and
 * identical text across sessions would collide.  This test uses reflection
 * to inspect the internal queue and ensure that two enqueue operations
 * with the same text but different session identifiers result in two
 * distinct entries whose keys are prefixed by their respective session ids.</p>
 */
public class SessionLeakTest {

    @Test
    public void identicalTextInDifferentSessionsCreatesDistinctKeys() throws Exception {
        // Create a VectorStoreService with null dependencies because we never flush in this test
        VectorStoreService svc = new VectorStoreService(null, null);
        // Enqueue the same text under two different session ids
        svc.enqueue("sessionA", "Hello world", Map.of());
        svc.enqueue("sessionB", "Hello world", Map.of());
        // Use reflection to access the private queue field
        Field qf = VectorStoreService.class.getDeclaredField("queue");
        qf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, ?> queue = (ConcurrentHashMap<String, ?>) qf.get(svc);
        // We expect two entries because deduplication is per session
        assertEquals(2, queue.size(), "Queue should contain two distinct entries for different sessions");
        // The dedupe keys should start with session id followed by ':'
        assertTrue(queue.keySet().stream().anyMatch(k -> k.startsWith("sessionA:")), "Key for sessionA missing");
        assertTrue(queue.keySet().stream().anyMatch(k -> k.startsWith("sessionB:")), "Key for sessionB missing");
    }
}