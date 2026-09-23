package com.example.lms.prompt.pose;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.aop.BraveQueryBurstAspect;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.budget.RetrievalBudgetGovernor;
import com.example.lms.service.rag.budget.RetrievalBudgetProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryBurstAnchorGateTest {

    @AfterEach
    void clear() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void braveQueryBurstDropsHighDriftCandidateAndTracesBudget() throws Throwable {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.orch.brave-query-burst.min", "1")
                .withProperty("nova.orch.brave-query-burst.max", "8")
                .withProperty("nova.orch.brave-query-burst.cap", "8");
        BraveQueryBurstAspect aspect = new BraveQueryBurstAspect(
                env,
                null,
                new AnchorNarrower(),
                null,
                new RetrievalBudgetGovernor(new RetrievalBudgetProperties()));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("expand.queryBurst.count", 8);
        GuardContextHolder.set(ctx);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "GraphRAG KG fusion", null, 8 });
        when(pjp.proceed(any(Object[].class))).thenReturn(List.of(
                "GraphRAG KG fusion",
                "unrelated cooking recipe"));

        Object out = aspect.expandQueriesInBraveMode(pjp);

        assertTrue(out instanceof List<?>);
        assertFalse(((List<?>) out).contains("unrelated cooking recipe"));
        assertTrue(TraceStore.get("rag.budget.applied.queryBurstCount") instanceof Number);
        assertTrue(TraceStore.get("rag.anchor.driftScore") instanceof Number);
    }

    @Test
    void budgetReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/BraveQueryBurstAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"web.brave.queryBurst.budget.reason\", decision.reason());"));
        assertFalse(source.contains(
                "TraceStore.put(\"web.brave.queryBurst.budget.reason\", SafeRedactor.safeMessage(decision.reason(), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"web.brave.queryBurst.budget.reason\", SafeRedactor.traceLabelOrFallback(decision.reason(), \"unknown\"));"));
    }

    @Test
    void braveQueryBurstDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/BraveQueryBurstAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Brave query burst needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
