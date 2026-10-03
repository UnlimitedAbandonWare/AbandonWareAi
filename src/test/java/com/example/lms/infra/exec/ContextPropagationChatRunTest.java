package com.example.lms.infra.exec;

import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.assist.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextPropagationChatRunTest {
    @Test void acceptedTaskScopeCrossesCallableAndRunnableAndCleansReusedWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var expired = new com.abandonware.ai.addons.budget.TimeBudget(1);
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(expired);
            Thread.sleep(5);
            try (var task = ChatRunExecutionContext.bindAcceptedTask()) {
                assertNull(com.abandonware.ai.addons.budget.TimeBudgetContext.get());
                var call = ContextPropagation.wrapCallable(() -> {
                    assertTrue(ChatRunExecutionContext.isAcceptedExecution());
                    assertNull(ChatRunExecutionContext.current());
                    assertNull(com.abandonware.ai.addons.budget.TimeBudgetContext.get());
                    return true;
                });
                assertTrue(worker.submit(call).get(1, TimeUnit.SECONDS));
                worker.submit(ContextPropagation.wrap(() ->
                        assertTrue(ChatRunExecutionContext.isAcceptedExecution()))).get(1, TimeUnit.SECONDS);
            }
            assertSame(expired, com.abandonware.ai.addons.budget.TimeBudgetContext.get());
            assertFalse(worker.submit(ChatRunExecutionContext::isAcceptedExecution).get(1, TimeUnit.SECONDS));
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            worker.shutdownNow(); worker.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test void callableCarriesExactRunAndPurposeScopeThenCleansWorker() throws Exception {
        ChatRunRegistry registry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var run = registry.beginOrJoin(8101L).context();
            var key = new JevChoiceAdvisor.QuestionKey(UUID.randomUUID(), 1, "synthetic");
            var admission = new JevEvaluationRuntime.DecisionAdmission(() -> true,
                    System.nanoTime()+TimeUnit.SECONDS.toNanos(3), true);
            try (var runBinding = ChatRunExecutionContext.bind(run);
                 var purpose = JevDecisionScope.bind("main", key, admission)) {
                var wrapped = ContextPropagation.wrapCallable(() -> {
                    assertSame(run, ChatRunExecutionContext.current());
                    assertSame(purpose, JevDecisionScope.capture());
                    return true;
                });
                assertTrue(worker.submit(wrapped).get(2, TimeUnit.SECONDS));
            }
            assertNull(worker.submit(ChatRunExecutionContext::current).get(1, TimeUnit.SECONDS));
            assertNull(worker.submit(JevDecisionScope::capture).get(1, TimeUnit.SECONDS));
        } finally { org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "shutdown"); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS); }
    }

    @Test void productionAssemblyContainsBothFixedAndDynamicGateCalls() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/example/lms/config/RetrieverChainConfig.java"));
        assertTrue(source.contains("JevRetrievalGateHandler.wrapIfEnabled(dyn, true"));
        assertTrue(source.contains("JevRetrievalGateHandler.wrapIfEnabled(h1, false"));
    }
}
