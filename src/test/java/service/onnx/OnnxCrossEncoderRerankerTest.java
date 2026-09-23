package service.onnx;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnnxCrossEncoderRerankerTest {

    @AfterEach
    void cleanup() {
        Thread.interrupted();
        TraceStore.clear();
    }

    @Test
    void interruptedAcquireDoesNotLeakPermitAndLeavesBreadcrumb() throws Exception {
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker();
        Semaphore limiter = limiter(reranker);
        assertEquals(1, limiter.availablePermits());

        Thread.currentThread().interrupt();
        List<String> result = reranker.rerank("query", List.of("a", "b"), ignored -> 1.0d, 1);

        assertEquals(List.of("a", "b"), result);
        assertEquals(1, limiter.availablePermits());
        assertEquals("limiter.acquire", TraceStore.get("onnx.reranker.suppressed.stage"));
        assertEquals("InterruptedException", TraceStore.get("onnx.reranker.suppressed.errorType"));
        assertEquals(true, TraceStore.get("onnx.reranker.suppressed.limiter.acquire"));
        assertEquals(true, TraceStore.get("onnx.reranker.fallbackUsed"));
    }

    private static Semaphore limiter(OnnxCrossEncoderReranker reranker) throws Exception {
        Field field = OnnxCrossEncoderReranker.class.getDeclaredField("limiter");
        field.setAccessible(true);
        return (Semaphore) field.get(reranker);
    }
}
