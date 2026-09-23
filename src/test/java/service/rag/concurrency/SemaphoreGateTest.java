package service.rag.concurrency;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemaphoreGateTest {

    @AfterEach
    void cleanup() {
        Thread.interrupted();
        TraceStore.clear();
    }

    @Test
    void interruptedAcquireLeavesBreadcrumbAndUsesFallback() {
        SemaphoreGate gate = new SemaphoreGate(1);
        Thread.currentThread().interrupt();

        String result = gate.tryWithPermit(() -> "critical", () -> "fallback", 25);

        assertEquals("fallback", result);
        assertEquals("tryAcquire", TraceStore.get("reranker.semaphore.suppressed.stage"));
        assertEquals("InterruptedException", TraceStore.get("reranker.semaphore.suppressed.errorType"));
        assertEquals(true, TraceStore.get("reranker.semaphore.suppressed.tryAcquire"));
        assertEquals(25, TraceStore.get("reranker.semaphore.timeoutMs"));
    }
}
