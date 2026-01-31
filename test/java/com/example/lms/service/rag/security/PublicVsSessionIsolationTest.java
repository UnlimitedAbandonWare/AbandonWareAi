package com.example.lms.service.rag.security;

import com.example.lms.service.VectorStoreService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that snippets indexed without a session id (public) are isolated
 * from those indexed with a session id.  Both entries should be stored
 * independently in the vector store and not deduplicated across the
 * public and session namespaces.  Prior implementations used a
 * wildcard "*" to represent the public namespace which allowed
 * cross-session collisions.  This test ensures that the transient
 * namespace ("__TRANSIENT__") is used for public snippets and that
 * identical content added under a specific session id remains distinct.
 */
public class PublicVsSessionIsolationTest {

    @Test
    public void publicAndSessionEntriesAreDistinct() throws Exception {
        VectorStoreService svc = new VectorStoreService(null, null);
        // Enqueue identical text in the public namespace (null session)
        svc.enqueue(null, "Common text", Map.of());
        // Enqueue identical text under a specific session id
        svc.enqueue("sess1", "Common text", Map.of());
        // Access the private queue via reflection
        Field qf = VectorStoreService.class.getDeclaredField("queue");
        qf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, ?> queue = (ConcurrentHashMap<String, ?>) qf.get(svc);
        // Expect two distinct entries
        assertEquals(2, queue.size(), "Queue should contain two entries for public and session scopes");
        // Keys should be prefixed with the transient namespace and the session id respectively
        assertTrue(queue.keySet().stream().anyMatch(k -> k.startsWith("__TRANSIENT__:")), "Public entry should use transient namespace");
        assertTrue(queue.keySet().stream().anyMatch(k -> k.startsWith("sess1:")), "Session entry should use its session id prefix");
    }
}