package com.example.lms.service.rag.security;

import com.example.lms.service.VectorStoreService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures that null or blank session identifiers are normalized to the
 * transient namespace rather than a wildcard.  Prior to the hardening,
 * missing session ids were converted to "*" which polluted the public index.
 */
public class NullSessionPublicTest {

    @Test
    public void nullSessionIdUsesTransientNamespace() throws Exception {
        VectorStoreService svc = new VectorStoreService(null, null);
        // Enqueue without a session id; this should normalize to "__TRANSIENT__"
        svc.enqueue(null, "Snippet with no session", Map.of());
        // Access the private queue
        Field qf = VectorStoreService.class.getDeclaredField("queue");
        qf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, ?> queue = (ConcurrentHashMap<String, ?>) qf.get(svc);
        // There should be one entry and its key should begin with the transient namespace
        assertEquals(1, queue.size(), "Queue should contain one entry");
        String key = queue.keySet().iterator().next();
        assertTrue(key.startsWith("__TRANSIENT__:"), "Missing session id should map to transient namespace, not '*'" );
    }
}