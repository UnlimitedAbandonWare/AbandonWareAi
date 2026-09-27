package com.example.lms.service.rag.langgraph;

import com.example.lms.api.ChatCancellationCommandHandler;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagOrchestratorFacadeExactCancelTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactStopDuringGraphFailureBlocksLegacyWhileInternalFailureRetainsIt(boolean stop) {
        ChatRunRegistry registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(73001L).context();
        AtomicInteger legacyCalls = new AtomicInteger();
        UnifiedRagOrchestrator legacy = new UnifiedRagOrchestrator() {
            @Override public QueryResponse query(QueryRequest request) {
                legacyCalls.incrementAndGet();
                return RagGraphExecutorTest.response("legacy", request);
            }
        };
        RagGraphExecutor graph = mock(RagGraphExecutor.class);
        when(graph.execute(any())).thenAnswer(invocation -> {
            if (stop) assertTrue(new ChatCancellationCommandHandler().cancel(73001L, run.clientToken(),
                    () -> true, registry, () -> {}).cancelled());
            throw new IllegalStateException("controlled stage failure");
        });
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        var request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "synthetic request";
        request.planId = "safe_autorun.v1";
        try (var scope = ChatRunExecutionContext.bind(run)) {
            if (stop) assertThrows(CancellationException.class, () -> facade.query(request));
            else assertEquals("legacy", facade.query(request).requestId);
            assertEquals(stop ? 0 : 1, legacyCalls.get());
        } finally {
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
            com.example.lms.search.TraceStore.clear();
        }
    }
}
