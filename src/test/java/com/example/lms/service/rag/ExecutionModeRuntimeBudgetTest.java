package com.example.lms.service.rag;

import com.example.lms.domain.enums.ExecutionMode;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionModeRuntimeBudgetTest {
    @AfterEach void clear() { TraceStore.clear(); }

    private Object begin(ExecutionMode mode) throws Exception {
        return SelfAskSearchBudget.class.getMethod("beginRequest", ExecutionMode.class).invoke(null, mode);
    }
    private boolean reserve(Map<String, Object> context, String query) throws Exception {
        return (boolean) SelfAskSearchBudget.class.getMethod("tryReserveHttp", Map.class, String.class)
                .invoke(null, context, query);
    }
    private void expand(Object budget) throws Exception {
        SelfAskSearchBudget.class.getMethod("allowExpansion", String.class).invoke(budget, "user-self-ask");
    }

    @Test void retriesAndBothProvidersShareSixDispatchesAcrossCapturedContext() throws Exception {
        Object budget = begin(ExecutionMode.SELF_ASK);
        expand(budget);
        Map<String, Object> captured = TraceStore.context();
        for (int i = 0; i < 6; i++) assertTrue(reserve(captured, "original"));
        TraceStore.clear(); // Naver retry keeps the captured request context.
        assertFalse(reserve(captured, "original"));
        assertTrue(reserve(TraceStore.context(), "independent legacy request"));
        assertFalse(captured.toString().contains("independent legacy request"));
    }

    @Test void canonicalQueriesReserveOnlyOriginalAndTwoAdditionalSlots() throws Exception {
        Object budget = begin(ExecutionMode.SELF_ASK);
        expand(budget);
        Map<String, Object> context = TraceStore.context();
        assertTrue(reserve(context, " Original  question "));
        assertTrue(reserve(context, "original question"));
        assertTrue(reserve(context, "relation one"));
        assertTrue(reserve(context, "relation two"));
        assertFalse(reserve(context, "fourth distinct query"));
        assertTrue(reserve(context, "relation one"));
        assertTrue(reserve(context, "relation two"));
        assertFalse(reserve(context, "original question"));
    }

    @Test void strikeCannotBePromotedAndDoesNotForgeHealthStrike() throws Exception {
        Object budget = begin(ExecutionMode.STRIKE);
        expand(budget);
        assertTrue(reserve(TraceStore.context(), "original"));
        assertFalse(reserve(TraceStore.context(), "optional expansion"));
        assertNull(TraceStore.get("orch.strike"));
        assertFalse(TraceStore.getAll().values().contains(budget), "internal mutable holder is never public trace");
    }

    @Test void concurrentDispatchesNeverExceedSix() throws Exception {
        begin(ExecutionMode.AUTO);
        Map<String, Object> context = TraceStore.context();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++) futures.add(pool.submit(() -> reserve(context, "original")));
            int accepted = 0;
            for (var future : futures) if (future.get(3, TimeUnit.SECONDS)) accepted++;
            assertEquals(6, accepted);
        } finally { pool.shutdownNow(); }
    }
}
